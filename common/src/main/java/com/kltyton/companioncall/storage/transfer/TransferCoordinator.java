package com.kltyton.companioncall.storage.transfer;

import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.api.CompanionCallApi;
import com.kltyton.companioncall.entity.ownership.RecallPermissions;
import com.kltyton.companioncall.mixin.access.EntityManagerAccess;
import com.kltyton.companioncall.mixin.access.PlayerSaveAccess;
import com.kltyton.companioncall.mixin.access.ServerLevelAccess;
import com.kltyton.companioncall.platform.ChunkLease;
import com.kltyton.companioncall.platform.NativeVersion;
import com.kltyton.companioncall.server.CompanionServer;
import com.kltyton.companioncall.world.recall.LandingFinder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** Same-identity native migration with durable source, destination, and rollback decisions. */
public final class TransferCoordinator implements AutoCloseable {
    private static final class Work {
        final UUID operation;
        final Entity source;
        final ServerLevel origin;
        final ServerLevel destination;
        final List<Entity> members;
        final Consumer<Entity> success;
        final Consumer<String> failure;
        final List<ChunkLease> leases = new ArrayList<>();
        Entity arrived;
        boolean migrated;
        boolean rollingBack;
        boolean destinationDecisionDurable;
        CompletableFuture<?> waiting;
        Runnable next;

        Work(UUID operation, Entity source, ServerLevel destination, List<Entity> members,
             Consumer<Entity> success, Consumer<String> failure) {
            this.operation = operation;
            this.source = source;
            this.origin = (ServerLevel) source.level();
            this.destination = destination;
            this.members = members;
            this.success = success;
            this.failure = failure;
        }
    }

    private final CompanionServer runtime;
    private final TransferLedger ledger;
    private final NativeChunkBarrier barriers = new NativeChunkBarrier();
    private final Map<UUID, Work> active = new HashMap<>();

    public TransferCoordinator(CompanionServer runtime, List<TransferRecord> records) {
        this.runtime = runtime;
        this.ledger = new TransferLedger(records);
    }

    public List<TransferRecord> records() { return ledger.records(); }
    public boolean locked(UUID entity) { return ledger.locked(entity); }
    public UUID operationFor(UUID entity) { return ledger.operation(entity); }
    public CompoundTag decorate(ServerLevel level, ChunkPos pos, CompoundTag tag) { return ledger.decorate(level, pos, tag); }
    public CompoundTag filterLoad(ServerLevel level, CompoundTag tag) { return ledger.filterLoad(level, tag); }

    public void begin(Entity source, ServerPlayer owner, LandingFinder.Landing landing,
                      Consumer<Entity> success, Consumer<String> failure) {
        var members = new ArrayList<Entity>();
        var parents = new HashMap<UUID, UUID>();
        collect(source, null, members, parents);
        if (source.isPassenger() || RecallPermissions.carriesOtherPlayer(source, owner.getUUID())) {
            failure.accept("pet_riding");
            return;
        }
        if (members.stream().anyMatch(member -> ledger.locked(member.getUUID()))) {
            failure.accept("busy");
            return;
        }
        UUID operation = UUID.randomUUID();
        var players = new HashMap<UUID, TransferRecord.PlayerOrigin>();
        var persistentMembers = new ArrayList<UUID>();
        for (var member : members) {
            if (member instanceof ServerPlayer) {
                players.put(member.getUUID(), new TransferRecord.PlayerOrigin(NativeVersion.dimension(member.level()),
                        member.getX(), member.getY(), member.getZ()));
            } else persistentMembers.add(member.getUUID());
        }
        var destination = (ServerLevel) owner.level();
        var position = landing.position();
        long protection = landing.protectedArrival() ? -1 : runtime.protectionDeadline(source.getUUID(), false);
        var record = new TransferRecord(operation, source.getUUID(), owner.getUUID(), NativeVersion.dimension(source.level()),
                source.getX(), source.getY(), source.getZ(), NativeVersion.dimension(destination),
                position.x, position.y, position.z, persistentMembers, parents, players,
                TransferRecord.Phase.PREPARED, protection);
        var work = new Work(operation, source, destination, members, success, failure);
        ledger.begin(record);
        active.put(operation, work);
        for (var member : members) ((TransferMark) member).companionCall$mark(operation.toString(), "source");
        work.leases.add(NativeVersion.retain(work.origin, ((int) Math.floor(record.sourceX())) >> 4,
                ((int) Math.floor(record.sourceZ())) >> 4, operation));
        work.leases.add(NativeVersion.retain(destination, ((int) Math.floor(position.x)) >> 4,
                ((int) Math.floor(position.z)) >> 4, operation));
        await(work, runtime.persist(), () -> prepareSource(work));
    }

    private void prepareSource(Work work) {
        var record = ledger.get(work.operation);
        var adapter = CompanionCallApi.adapter(work.source);
        if (adapter == null || !work.source.isAlive() || !record.owner().equals(adapter.owner(work.source))) {
            abortBeforeMigration(work, "not_owned");
            return;
        }
        StorageHooks.internally(() -> {
            NativeVersion.closeViewers(work.source);
            adapter.beforeTransfer(work.source, work.destination);
            if (!record.owner().equals(adapter.owner(work.source))) throw new IllegalStateException("Owner changed in transfer callback");
            return null;
        });
        await(work, barriers.save(work.origin, sourceChunk(record), runtime.gameTime()), () -> {
            if (!ledger.witnessed(work.operation)) throw new IllegalStateException("Native entity snapshot did not preserve the source");
            ledger.phase(work.operation, TransferRecord.Phase.SOURCE_DURABLE);
            await(work, runtime.persist(), () -> migrate(work));
        });
    }

    public void tick() {
        barriers.tick(runtime.gameTime());
        recoverPlayers();
        for (var work : new ArrayList<>(active.values())) {
            if (work.waiting == null || !work.waiting.isDone()) continue;
            var continuation = work.next;
            var finished = work.waiting;
            work.waiting = null;
            work.next = null;
            try {
                finished.join();
                continuation.run();
            } catch (RuntimeException error) {
                CompanionCall.LOGGER.error("Recall transfer {} failed", work.operation, error);
                if (!active.containsKey(work.operation)) throw error;
                fail(work);
            }
        }
    }

    private void await(Work work, CompletableFuture<?> future, Runnable continuation) {
        work.waiting = future;
        work.next = continuation;
    }

    private void migrate(Work work) {
        var record = ledger.get(work.operation);
        var owner = runtime.server.getPlayerList().getPlayer(record.owner());
        var adapter = CompanionCallApi.adapter(work.source);
        if (owner == null || !owner.isAlive() || !record.destinationDimension().equals(NativeVersion.dimension(owner.level()))
                || !record.pet().equals(runtime.index.selected(record.owner()))
                || adapter == null || !record.owner().equals(adapter.owner(work.source)) || !work.source.isAlive()) {
            abortBeforeMigration(work, "cancelled");
            return;
        }
        if (work.source.isPassenger() || RecallPermissions.carriesOtherPlayer(work.source, record.owner())) {
            abortBeforeMigration(work, "pet_riding");
            return;
        }
        StorageHooks.internally(() -> {
            if (!record.owner().equals(adapter.owner(work.source))) throw new IllegalStateException("Owner changed in transfer callback");
            work.migrated = true;
            work.arrived = NativeVersion.transfer(work.source, work.destination,
                    new Vec3(record.destinationX(), record.destinationY(), record.destinationZ()));
            if (work.arrived == null || !record.pet().equals(work.arrived.getUUID()) || work.arrived.level() != work.destination) {
                throw new IllegalStateException("Native migration did not retain pet identity");
            }
            var arrivedMembers = new ArrayList<Entity>();
            var arrivedParents = new HashMap<UUID, UUID>();
            collect(work.arrived, null, arrivedMembers, arrivedParents);
            var actual = arrivedMembers.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet());
            var expected = new HashSet<>(record.members());
            expected.addAll(record.players().keySet());
            if (!actual.equals(expected) || !arrivedParents.equals(record.parents())) {
                throw new IllegalStateException("Native migration changed passenger relationships");
            }
            for (var member : arrivedMembers) {
                var original = work.members.stream().filter(entity -> entity.getUUID().equals(member.getUUID())).findFirst().orElseThrow();
                if (member.getType() != original.getType()) throw new IllegalStateException("Native migration changed entity type");
            }
            for (var member : arrivedMembers) ((TransferMark) member).companionCall$mark(work.operation.toString(), "destination");
            var arrivedAdapter = CompanionCallApi.adapter(work.arrived);
            if (arrivedAdapter == null || !record.owner().equals(arrivedAdapter.owner(work.arrived))) {
                throw new IllegalStateException("Native migration changed ownership");
            }
            work.arrived.setDeltaMovement(Vec3.ZERO);
            work.arrived.resetFallDistance();
            arrivedAdapter.afterTransfer(work.arrived, work.origin);
            if (!record.owner().equals(arrivedAdapter.owner(work.arrived)) || !work.arrived.isAlive()) {
                throw new IllegalStateException("Transfer callback changed owner or life state");
            }
            runtime.recalls.transferred(work.source, work.arrived);
            if (record.protectionDeadline() < 0) {
                ledger.replace(record.protection(runtime.protectionDeadline(record.pet(), true)));
            }
            for (UUID player : record.players().keySet()) savePlayer(player);
            return null;
        });
        await(work, barriers.save(work.destination, destinationChunk(record), runtime.gameTime()), () -> {
            ledger.phase(work.operation, TransferRecord.Phase.DESTINATION_DURABLE);
            await(work, runtime.persist(), () -> {
                work.destinationDecisionDurable = true;
                ledger.releaseSource(work.operation);
                await(work, barriers.save(work.origin, sourceChunk(record), runtime.gameTime()), () -> {
                    ledger.phase(work.operation, TransferRecord.Phase.COMMITTED);
                    await(work, runtime.persist(), () -> {
                        release(work);
                        work.success.accept(work.arrived);
                    });
                });
            });
        });
    }

    private void fail(Work work) {
        if (work.rollingBack) {
            retainForRecovery(work);
            work.failure.accept("recovery_conflict");
            return;
        }
        var phase = ledger.get(work.operation).phase();
        if (work.destinationDecisionDurable || phase == TransferRecord.Phase.COMMITTED) {
            // A durable destination is never rolled back into an old source snapshot.
            retainForRecovery(work);
            work.failure.accept("persistence_failed");
            return;
        }
        if (!work.migrated) {
            abortBeforeMigration(work, "persistence_failed");
            return;
        }
        work.rollingBack = true;
        ledger.phase(work.operation, TransferRecord.Phase.ABORTED);
        await(work, runtime.persist(), () -> rollback(work));
    }

    private void abortBeforeMigration(Work work, String reason) {
        work.rollingBack = true;
        ledger.phase(work.operation, TransferRecord.Phase.ABORTED);
        await(work, runtime.persist(), () -> {
            release(work);
            work.failure.accept(reason);
        });
    }

    private void rollback(Work work) {
        var record = ledger.get(work.operation);
        var position = sourceChunk(record);
        var manager = ((ServerLevelAccess) work.origin).companionCall$entities();
        var storage = (EntityStorageAccess) ((EntityManagerAccess) manager).companionCall$storage();
        ledger.recoveryRead(work.origin, position, work.operation);
        var read = storage.companionCall$read(position);
        await(work, read, () -> {
            ledger.finishRead(work.origin, position);
            var restored = read.join().getEntities().toList();
            var ids = restored.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet());
            if (restored.size() != ids.size() || !ids.equals(new HashSet<>(record.members()))) {
                throw new IllegalStateException("Native source recovery was incomplete");
            }
            var root = restored.stream().filter(entity -> record.pet().equals(entity.getUUID())).findFirst().orElseThrow();
            var sourceAdapter = CompanionCallApi.adapter(root);
            if (sourceAdapter == null || !root.isAlive() || !record.owner().equals(sourceAdapter.owner(root))) {
                throw new IllegalStateException("Native source recovery did not preserve ownership and life state");
            }
            var recoveredParents = new HashMap<UUID, UUID>();
            collect(root, null, new ArrayList<>(), recoveredParents);
            var expectedParents = new HashMap<>(record.parents());
            record.players().keySet().forEach(expectedParents::remove);
            if (!recoveredParents.equals(expectedParents)) throw new IllegalStateException("Native source recovery changed passenger relationships");
            for (var member : restored) {
                var original = work.members.stream().filter(entity -> entity.getUUID().equals(member.getUUID())).findFirst().orElseThrow();
                if (member.getType() != original.getType()) throw new IllegalStateException("Native source recovery changed entity type");
            }
            var touched = new HashMap<ServerLevel, java.util.Set<ChunkPos>>();
            touched.computeIfAbsent(work.origin, level -> new HashSet<>()).add(position);
            touched.computeIfAbsent(work.destination, level -> new HashSet<>()).add(destinationChunk(record));
            var live = new ArrayList<Entity>();
            for (UUID id : record.members()) {
                for (var level : runtime.server.getAllLevels()) {
                    var existing = level.getEntity(id);
                    if (existing == null) continue;
                    if (!work.operation.toString().equals(((TransferMark) existing).companionCall$nonce())) {
                        throw new IllegalStateException("Unknown live entity blocks transfer recovery");
                    }
                    var chunk = existing.chunkPosition();
                    touched.computeIfAbsent(level, ignored -> new HashSet<>()).add(chunk);
                    work.leases.add(NativeVersion.retain(level, existing.blockPosition().getX() >> 4,
                            existing.blockPosition().getZ() >> 4, work.operation));
                    live.add(existing);
                }
            }
            StorageHooks.internally(() -> {
                for (var existing : live) existing.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                manager.addLegacyChunkEntities(restored.stream());
                for (var entity : restored) {
                    if (work.origin.getEntity(entity.getUUID()) != entity) {
                        throw new IllegalStateException("Native manager rejected recovered entity");
                    }
                }
                for (var entry : record.players().entrySet()) {
                    var player = runtime.server.getPlayerList().getPlayer(entry.getKey());
                    if (player == null) continue;
                    restorePlayer(player, entry.getValue());
                    UUID parent = record.parents().get(player.getUUID());
                    var vehicle = parent == null ? null : work.origin.getEntity(parent);
                    if (vehicle != null && !player.isPassenger()) NativeVersion.mount(player, vehicle, true);
                    ((TransferMark) player).companionCall$mark("", "");
                    savePlayer(player.getUUID());
                }
                return null;
            });
            ledger.releaseSource(work.operation);
            var writes = new ArrayList<CompletableFuture<Void>>();
            touched.forEach((level, chunks) -> chunks.forEach(chunk -> writes.add(barriers.save(level, chunk, runtime.gameTime()))));
            var saves = CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
            await(work, saves, () -> await(work, runtime.persist(), () -> {
                release(work);
                work.failure.accept("transfer_failed");
            }));
        });
    }

    private void recoverPlayers() {
        for (var player : runtime.server.getPlayerList().getPlayers()) {
            if (ledger.locked(player.getUUID())) continue;
            var mark = (TransferMark) player;
            if (mark.companionCall$nonce().isEmpty()) continue;
            TransferRecord record;
            try { record = ledger.get(UUID.fromString(mark.companionCall$nonce())); }
            catch (IllegalArgumentException error) { continue; }
            if (record == null || !record.players().containsKey(player.getUUID())) continue;
            if (record.phase() == TransferRecord.Phase.ABORTED) {
                StorageHooks.internally(() -> {
                    restorePlayer(player, record.players().get(player.getUUID()));
                    return null;
                });
            }
            mark.companionCall$mark("", "");
            savePlayer(player.getUUID());
        }
    }

    private void restorePlayer(ServerPlayer player, TransferRecord.PlayerOrigin origin) {
        var level = runtime.server.getLevel(NativeVersion.levelKey(origin.dimension()));
        if (level == null) throw new IllegalStateException("Player recovery dimension is unavailable");
        NativeVersion.transfer(player, level, new Vec3(origin.x(), origin.y(), origin.z()));
    }

    private void savePlayer(UUID id) {
        var player = runtime.server.getPlayerList().getPlayer(id);
        if (player != null) ((PlayerSaveAccess) runtime.server.getPlayerList()).companionCall$save(player);
    }

    private void release(Work work) {
        ledger.finishRead(work.origin, sourceChunk(ledger.get(work.operation)));
        ledger.unlock(work.operation);
        active.remove(work.operation);
        for (var lease : work.leases) lease.close();
        work.leases.clear();
    }

    private void retainForRecovery(Work work) {
        ledger.finishRead(work.origin, sourceChunk(ledger.get(work.operation)));
        active.remove(work.operation);
        for (var lease : work.leases) lease.close();
        work.leases.clear();
        // Proven witnesses and identity reservations remain until native recovery or server shutdown.
    }

    private static void collect(Entity entity, Entity parent, List<Entity> members, Map<UUID, UUID> parents) {
        members.add(entity);
        if (parent != null) parents.put(entity.getUUID(), parent.getUUID());
        for (var passenger : entity.getPassengers()) collect(passenger, entity, members, parents);
    }

    private static ChunkPos sourceChunk(TransferRecord record) {
        return new ChunkPos(((int) Math.floor(record.sourceX())) >> 4, ((int) Math.floor(record.sourceZ())) >> 4);
    }

    private static ChunkPos destinationChunk(TransferRecord record) {
        return new ChunkPos(((int) Math.floor(record.destinationX())) >> 4, ((int) Math.floor(record.destinationZ())) >> 4);
    }

    @Override public void close() {
        for (var work : new ArrayList<>(active.values())) retainForRecovery(work);
    }
}
