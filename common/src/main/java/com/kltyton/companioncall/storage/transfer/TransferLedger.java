package com.kltyton.companioncall.storage.transfer;

import com.kltyton.companioncall.platform.NativeVersion;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Proven transfer identities and source witnesses, independent of whether an entity is loaded. */
public final class TransferLedger {
    private record Chunk(String dimension, long position) {}
    private final Map<UUID, TransferRecord> history = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> locks = new ConcurrentHashMap<>();
    private final Set<UUID> protectedSources = ConcurrentHashMap.newKeySet();
    private final Map<UUID, CompoundTag> witnesses = new ConcurrentHashMap<>();
    private final Map<Chunk, UUID> recoveryReads = new ConcurrentHashMap<>();

    public TransferLedger(List<TransferRecord> records) {
        for (var record : records) {
            var phase = record.phase();
            if (phase == TransferRecord.Phase.PREPARED || phase == TransferRecord.Phase.SOURCE_DURABLE) {
                record = record.phase(TransferRecord.Phase.ABORTED);
            }
            history.put(record.operation(), record);
        }
    }

    public List<TransferRecord> records() { return new ArrayList<>(history.values()); }
    public TransferRecord get(UUID operation) { return history.get(operation); }
    public void replace(TransferRecord record) { history.put(record.operation(), record); }
    public boolean locked(UUID entity) { return locks.containsKey(entity); }
    public UUID operation(UUID entity) { return locks.get(entity); }

    public void begin(TransferRecord record) {
        var ids = new HashSet<>(record.members());
        ids.addAll(record.players().keySet());
        if (ids.stream().anyMatch(locks::containsKey)) throw new IllegalStateException("Transfer member is already reserved");
        ids.forEach(id -> locks.put(id, record.operation()));
        history.put(record.operation(), record);
        protectedSources.add(record.operation());
    }

    public void phase(UUID operation, TransferRecord.Phase phase) {
        history.compute(operation, (ignored, record) -> record.phase(phase));
    }

    public void releaseSource(UUID operation) { protectedSources.remove(operation); }

    public void unlock(UUID operation) {
        locks.entrySet().removeIf(entry -> operation.equals(entry.getValue()));
        protectedSources.remove(operation);
        witnesses.remove(operation);
    }

    public boolean witnessed(UUID operation) { return witnesses.containsKey(operation); }

    public void recoveryRead(ServerLevel level, ChunkPos pos, UUID operation) {
        recoveryReads.put(new Chunk(NativeVersion.dimension(level), NativeVersion.chunkKey(pos)), operation);
    }

    public void finishRead(ServerLevel level, ChunkPos pos) {
        recoveryReads.remove(new Chunk(NativeVersion.dimension(level), NativeVersion.chunkKey(pos)));
    }

    public CompoundTag decorate(ServerLevel level, ChunkPos pos, CompoundTag supplied) {
        if (supplied == null && protectedSources.stream().noneMatch(operation -> {
            var record = history.get(operation);
            return record.sourceDimension().equals(NativeVersion.dimension(level))
                    && NativeVersion.chunkKey(pos) == sourceChunk(record);
        })) return null;
        var tag = supplied == null ? NativeVersion.chunkTag(pos) : supplied.copy();
        String dimension = NativeVersion.dimension(level);
        var entities = NativeVersion.list(tag, "Entities");
        for (int i = 0; i < entities.size(); i++) {
            if (!(entities.get(i) instanceof CompoundTag entity)) continue;
            var record = recognized(entity);
            if (record != null && record.pet().equals(NativeVersion.uuid(entity))
                    && dimension.equals(record.sourceDimension())
                    && NativeVersion.chunkKey(pos) == sourceChunk(record)
                    && "source".equals(NativeVersion.string(entity, StorageHooks.ROLE))
                    && protectedSources.contains(record.operation())) {
                var ids = new HashSet<UUID>();
                collectIds(entity, ids);
                if (!ids.equals(Set.copyOf(record.members()))) {
                    throw new IllegalStateException("Native source snapshot omitted transfer members");
                }
                witnesses.putIfAbsent(record.operation(), entity.copy());
            }
        }
        var filtered = filterEntities(entities, dimension, pos, null, false);
        for (UUID operation : protectedSources) {
            var record = history.get(operation);
            var witness = witnesses.get(operation);
            if (witness == null || !dimension.equals(record.sourceDimension())
                    || NativeVersion.chunkKey(pos) != sourceChunk(record)) continue;
            for (int i = filtered.size() - 1; i >= 0; i--) {
                if (filtered.get(i) instanceof CompoundTag existing && record.pet().equals(NativeVersion.uuid(existing))
                        && "source".equals(NativeVersion.string(existing, StorageHooks.ROLE))) {
                    filtered.remove(i);
                }
            }
            filtered.add(witness.copy());
        }
        tag.put("Entities", filtered);
        return tag;
    }

    public CompoundTag filterLoad(ServerLevel level, CompoundTag supplied) {
        var pos = NativeVersion.storedChunk(supplied);
        String dimension = NativeVersion.dimension(level);
        UUID recovery = recoveryReads.get(new Chunk(dimension, NativeVersion.chunkKey(pos)));
        var tag = supplied.copy();
        tag.put("Entities", filterEntities(NativeVersion.list(tag, "Entities"), dimension, pos, recovery, true));
        return tag;
    }

    private ListTag filterEntities(ListTag entities, String dimension, ChunkPos pos, UUID onlyOperation, boolean loading) {
        var result = new ListTag();
        for (var value : entities) {
            if (!(value instanceof CompoundTag entity)) continue;
            var record = recognized(entity);
            if (onlyOperation != null && (record == null || !onlyOperation.equals(record.operation()))) continue;
            if (record != null) {
                String role = NativeVersion.string(entity, StorageHooks.ROLE);
                boolean source = role.equals("source") && dimension.equals(record.sourceDimension())
                        && NativeVersion.chunkKey(pos) == sourceChunk(record);
                boolean committed = record.phase() == TransferRecord.Phase.DESTINATION_DURABLE
                        || record.phase() == TransferRecord.Phase.COMMITTED || record.phase() == TransferRecord.Phase.DEAD;
                boolean destination = role.equals("destination") && (committed || dimension.equals(record.destinationDimension()));
                boolean recoveredSource = role.equals("source") && record.phase() == TransferRecord.Phase.ABORTED;
                if (!source && !destination && !recoveredSource) {
                    throw new IllegalStateException("Conflicting recall transfer provenance: " + record.operation());
                }
                if (source && committed && !protectedSources.contains(record.operation())) continue;
                if (destination && !committed && (loading || record.phase() == TransferRecord.Phase.ABORTED)) continue;
                if (onlyOperation != null && (!source || !record.pet().equals(NativeVersion.uuid(entity)))) continue;
            }
            var copy = entity.copy();
            var passengers = NativeVersion.list(copy, "Passengers");
            if (!passengers.isEmpty()) copy.put("Passengers", filterEntities(passengers, dimension, pos, null, loading));
            result.add(copy);
        }
        return result;
    }

    private TransferRecord recognized(CompoundTag entity) {
        String nonce = NativeVersion.string(entity, StorageHooks.NONCE);
        if (nonce.isEmpty()) return null;
        UUID operation;
        try { operation = UUID.fromString(nonce); }
        catch (IllegalArgumentException error) { return null; }
        var record = history.get(operation);
        if (record == null || !record.members().contains(NativeVersion.uuid(entity))) return null;
        return record;
    }

    private static long sourceChunk(TransferRecord record) {
        return NativeVersion.chunkKey(new ChunkPos(((int) Math.floor(record.sourceX())) >> 4,
                ((int) Math.floor(record.sourceZ())) >> 4));
    }

    private static void collectIds(CompoundTag entity, Set<UUID> ids) {
        UUID id = NativeVersion.uuid(entity);
        if (id != null && !ids.add(id)) throw new IllegalStateException("Repeated UUID in native transfer snapshot");
        for (var child : NativeVersion.list(entity, "Passengers")) if (child instanceof CompoundTag tag) collectIds(tag, ids);
    }
}
