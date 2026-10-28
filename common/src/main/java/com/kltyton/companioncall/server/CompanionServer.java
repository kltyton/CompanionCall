package com.kltyton.companioncall.server;

import com.google.gson.Gson;
import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.api.CompanionCallApi;
import com.kltyton.companioncall.api.PetAdapter;
import com.kltyton.companioncall.api.OwnerChangeResult;
import com.kltyton.companioncall.entity.ownership.BoundEntityAdapter;
import com.kltyton.companioncall.entity.ownership.ManualOwnership;
import com.kltyton.companioncall.config.RecallConfig;
import com.kltyton.companioncall.config.CompanionConfigSpec;
import com.kltyton.companioncall.entity.appearance.PetAppearance;
import com.kltyton.companioncall.network.payload.ServerSnapshot;
import com.kltyton.companioncall.navigation.NavigationScheduler;
import com.kltyton.companioncall.platform.NativeVersion;
import com.kltyton.companioncall.server.recall.RecallService;
import com.kltyton.companioncall.storage.RecallStore;
import com.kltyton.companioncall.storage.pet.PetIndex;
import com.kltyton.companioncall.storage.pet.PetRecord;
import com.kltyton.companioncall.storage.transfer.TransferCoordinator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.LevelResource;

public final class CompanionServer implements AutoCloseable {
    private static final Gson GSON = new Gson();
    public final MinecraftServer server;
    public final RecallConfig config;
    public final PetIndex index;
    public final TransferCoordinator transfers;
    public final RecallService recalls;
    private final RecallStore store;
    private final Map<UUID, Long> protections = new HashMap<>();
    private final Map<UUID, Long> sentRevision = new HashMap<>();
    private final Map<UUID, Long> appearanceRefresh = new HashMap<>();
    private final Map<UUID, PetRecord> riddenPreviews = new HashMap<>();
    private long persistedRevision = -1;
    private int ticks;

    public CompanionServer(MinecraftServer server) {
        this.server = server;
        try {
            Path directory = server.getWorldPath(LevelResource.ROOT).resolve("data/companion_call");
            config = CompanionConfigSpec.commonValues();
            store = new RecallStore(directory);
            var snapshot = store.read();
            index = new PetIndex(snapshot.pets(), snapshot.selections(), snapshot.nextOrder(), snapshot.blacklists(), snapshot.bindings());
            protections.putAll(snapshot.protections());
            transfers = new TransferCoordinator(this, snapshot.transfers());
            recalls = new RecallService(this);
        } catch (IOException error) {
            throw new UncheckedIOException("Cannot open companion recall data", error);
        }
    }

    public void observe(Entity entity) {
        observe(entity, false);
    }

    public void captureAppearance(Entity entity) {
        observe(entity, true);
    }

    private void observe(Entity entity, boolean captureAppearance) {
        if (!(entity.level() instanceof ServerLevel) || !entity.isAlive() || entity.isRemoved()) return;
        var adapter = CompanionCallApi.adapter(entity);
        if (adapter == null) return;
        UUID owner = adapter.owner(entity);
        if (owner == null) {
            index.invalidate(entity.getUUID());
            return;
        }
        if (adapter != BoundEntityAdapter.INSTANCE) {
            index.unbind(entity.getUUID());
            ManualOwnership.set(entity, null);
        } else if (index.bindingOwner(entity.getUUID()) != null) {
            ManualOwnership.set(entity, owner);
        }
        var previous = index.get(entity.getUUID());
        String appearance = captureAppearance || previous == null || previous.appearance() == null
                || !owner.equals(previous.owner()) ? PetAppearance.capture(entity, adapter) : previous.appearance();
        index.observe(record(entity, owner, index.order(entity.getUUID(), owner), appearance, adapter));
    }

    private PetRecord record(Entity entity, UUID owner, long order, String appearance, PetAdapter adapter) {
        float health = entity instanceof LivingEntity living ? living.getHealth() : 0;
        float maxHealth = entity instanceof LivingEntity living ? living.getMaxHealth() : 0;
        return new PetRecord(entity.getUUID(), owner, order,
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), entity.getName().getString(),
                NativeVersion.dimension(entity.level()), entity.getX(), entity.getY(), entity.getZ(),
                health, maxHealth, adapter.isSitting(entity), adapter.travelModes(entity),
                NativeVersion.speed(entity), NativeVersion.jump(entity), entity.isAlive(), appearance);
    }

    public void tick() {
        ticks++;
        transfers.tick();
        recalls.tick();
        NavigationScheduler.tick(server);
        if (ticks % 20 == 0) {
            for (var level : server.getAllLevels()) for (var entity : level.getAllEntities()) observe(entity);
            protections.entrySet().removeIf(entry -> entry.getValue() <= gameTime());
            for (var player : server.getPlayerList().getPlayers()) {
                if (sentRevision.getOrDefault(player.getUUID(), -1L) != index.revision()) sync(player);
            }
            sentRevision.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            appearanceRefresh.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            riddenPreviews.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            if (persistedRevision != index.revision()) persist();
        }
    }

    public long gameTime() { return server.overworld().getGameTime(); }

    public boolean protectedFromEnvironmentalDamage(UUID pet) {
        return protections.getOrDefault(pet, -1L) > gameTime();
    }

    public long protectionDeadline(UUID pet, boolean needed) {
        if (!needed) return protections.getOrDefault(pet, 0L);
        long existing = protections.getOrDefault(pet, 0L);
        long deadline = existing > gameTime() ? existing : gameTime() + config.protectionTicks();
        protections.put(pet, deadline);
        return deadline;
    }

    public void death(Entity entity) {
        ManualOwnership.set(entity, null);
        index.unbind(entity.getUUID());
        index.invalidate(entity.getUUID());
        recalls.cancel(entity.getUUID(), "dead");
        persist();
    }

    public CompletableFuture<Void> persist() {
        persistedRevision = index.revision();
        var snapshot = new RecallStore.Snapshot(2, index.nextOrder(), index.records(), index.selections(),
                transfers.records(), Map.copyOf(protections), index.blacklists(), index.bindings());
        return store.write(snapshot).whenComplete((ignored, error) -> {
            if (error != null) CompanionCall.LOGGER.error("Could not persist companion recall state", error);
        });
    }

    public void request(ServerPlayer player, String action, String id) {
        switch (action) {
            case "refresh" -> {
                long now = gameTime();
                // Full visual serialization is limited to one explicit refresh per owner per second.
                if (now - appearanceRefresh.getOrDefault(player.getUUID(), -20L) >= 20) {
                    appearanceRefresh.put(player.getUUID(), now);
                    for (var pet : index.owned(player.getUUID())) {
                        var entity = findLoaded(pet.id());
                        if (entity != null) captureAppearance(entity);
                    }
                    for (var pet : index.blocked(player.getUUID())) {
                        var entity = findLoaded(pet.id());
                        if (entity != null) captureAppearance(entity);
                    }
                    Entity ridden = player.getVehicle();
                    if (ridden != null) {
                        var adapter = CompanionCallApi.adapter(ridden);
                        riddenPreviews.put(player.getUUID(), record(ridden, adapter.owner(ridden), 0,
                                PetAppearance.capture(ridden, adapter), adapter));
                    } else riddenPreviews.remove(player.getUUID());
                }
                sync(player);
            }
            case "recall" -> recalls.request(player);
            case "add_ridden" -> {
                Entity ridden = player.getVehicle();
                if (ridden == null) recalls.status(player, "not_riding");
                else bind(player, ridden);
            }
            case "blacklist", "restore" -> {
                try {
                    UUID entityId = UUID.fromString(id);
                    Entity loaded = findLoaded(entityId);
                    if (loaded != null) observe(loaded);
                    if (!index.blacklist(player.getUUID(), entityId, "blacklist".equals(action))) {
                        recalls.status(player, "not_owned");
                        return;
                    }
                    if ("blacklist".equals(action)) recalls.cancel(entityId, "blacklisted");
                    persist();
                    recalls.status(player, "blacklist".equals(action) ? "blacklisted" : "restored");
                } catch (IllegalArgumentException error) {
                    recalls.status(player, "invalid_request");
                }
            }
            case "select" -> {
                try {
                    if (!index.select(player.getUUID(), UUID.fromString(id))) {
                        recalls.status(player, "not_owned");
                        return;
                    }
                    persist();
                    sync(player);
                } catch (IllegalArgumentException error) {
                    recalls.status(player, "invalid_request");
                }
            }
            default -> recalls.status(player, "invalid_request");
        }
    }

    public void sync(ServerPlayer player) {
        record State(int holdMillis, UUID selected, String status, java.util.List<PetRecord> pets,
                     java.util.Set<UUID> loaded, java.util.List<PetRecord> blocked, PetRecord ridden) {}
        var pets = index.owned(player.getUUID());
        var blocked = index.blocked(player.getUUID());
        var loaded = new java.util.HashSet<UUID>();
        for (var pet : pets) if (findLoaded(pet.id()) != null) loaded.add(pet.id());
        for (var pet : blocked) if (findLoaded(pet.id()) != null) loaded.add(pet.id());
        PetRecord riddenRecord = null;
        Entity ridden = player.getVehicle();
        if (ridden != null) {
            var adapter = CompanionCallApi.adapter(ridden);
            var cached = riddenPreviews.get(player.getUUID());
            var registered = index.get(ridden.getUUID());
            String appearance = cached != null && cached.id().equals(ridden.getUUID()) ? cached.appearance()
                    : registered == null ? null : registered.appearance();
            riddenRecord = record(ridden, adapter.owner(ridden), 0, appearance, adapter);
            loaded.add(ridden.getUUID());
        }
        new ServerSnapshot(GSON.toJson(new State(config.holdMillis(), index.selected(player.getUUID()),
                recalls.status(player.getUUID()), pets, loaded, blocked, riddenRecord))).sendTo(player);
        sentRevision.put(player.getUUID(), index.revision());
    }

    public Entity findLoaded(UUID id) {
        for (var level : server.getAllLevels()) {
            var entity = level.getEntity(id);
            if (entity != null) return entity;
        }
        return null;
    }

    public boolean bind(ServerPlayer player, Entity entity) {
        boolean operator = NativeVersion.canManage(player.createCommandSourceStack());
        if (player.getVehicle() != entity && !operator) {
            recalls.status(player, "not_riding");
            return false;
        }
        if (!entity.isAlive() || entity.isRemoved() || entity.level() != player.level() || transfers.locked(entity.getUUID())) {
            recalls.status(player, "unavailable");
            return false;
        }
        var adapter = CompanionCallApi.adapter(entity);
        UUID previousOwner = adapter.owner(entity);
        if (previousOwner != null && !operator) {
            recalls.status(player, "owned_requires_admin");
            return false;
        }
        if (!player.getUUID().equals(previousOwner)) {
            OwnerChangeResult result = adapter.setOwner(entity, player);
            if (result == OwnerChangeResult.REJECTED || result == OwnerChangeResult.UNSUPPORTED && previousOwner != null) {
                recalls.status(player, "not_owned");
                return false;
            }
            if (result == OwnerChangeResult.UNSUPPORTED) {
                index.bind(entity.getUUID(), player.getUUID());
                ManualOwnership.set(entity, player.getUUID());
            }
        }
        recalls.cancel(entity.getUUID(), "owner_changed");
        captureAppearance(entity);
        index.blacklist(player.getUUID(), entity.getUUID(), false);
        index.select(player.getUUID(), entity.getUUID());
        persist();
        recalls.status(player, "registered");
        if (previousOwner != null && !previousOwner.equals(player.getUUID())) {
            var previousPlayer = server.getPlayerList().getPlayer(previousOwner);
            if (previousPlayer != null) sync(previousPlayer);
        }
        return true;
    }

    @Override public void close() {
        recalls.close();
        transfers.close();
        NavigationScheduler.close(server);
        persist();
        try {
            store.close();
        } catch (IOException error) {
            throw new UncheckedIOException("Could not finish saving companion state", error);
        }
    }
}
