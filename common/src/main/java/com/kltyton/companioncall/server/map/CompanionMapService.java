package com.kltyton.companioncall.server.map;

import com.kltyton.companioncall.api.CompanionCallApi;
import com.kltyton.companioncall.api.PetAdapter;
import com.kltyton.companioncall.entity.navigation.MapMove;
import com.kltyton.companioncall.entity.ownership.RecallPermissions;
import com.kltyton.companioncall.network.map.CompanionMapProtocol;
import com.kltyton.companioncall.network.payload.ServerSnapshot;
import com.kltyton.companioncall.platform.NativeVersion;
import com.kltyton.companioncall.server.CompanionServer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Server-thread authority for map discovery and orders. */
public final class CompanionMapService {
    private static final int MAX_RANGE = 96;
    private static final int MOVE_INTERVAL = 5;
    private static final int ORDER_LIFETIME = 1800;

    private final CompanionServer runtime;
    private final Map<UUID, Order> active = new HashMap<>();
    private final Map<UUID, Long> listRequests = new HashMap<>();
    private final Map<UUID, Long> moveRequests = new HashMap<>();

    private record Order(ServerPlayer player, BlockPos goal, Entity entity, PetAdapter adapter, MapMove movement) {}

    public CompanionMapService(CompanionServer runtime) { this.runtime = runtime; }

    public void list(ServerPlayer player, String query) {
        if (!ready(player)) return;
        long now = System.nanoTime();
        Long previous = listRequests.get(player.getUUID());
        // The task queue can service read-only requests while the integrated world is paused.
        if (previous != null && now - previous < CompanionMapProtocol.LIST_INTERVAL_NANOS) return;
        listRequests.put(player.getUUID(), now);
        UUID token;
        try {
            token = UUID.fromString(query);
        } catch (IllegalArgumentException error) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        var entries = new ArrayList<CompanionMapProtocol.Companion>();
        for (var record : runtime.index.owned(player.getUUID())) {
            Entity entity = level.getEntity(record.id());
            if (eligible(player, entity) == null || runtime.recalls.isActive(record.id())) continue;
            entries.add(new CompanionMapProtocol.Companion(record.id(), entity.getId(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
                    entity.getName().getString(), entity.getX(), entity.getY(), entity.getZ()));
        }
        new ServerSnapshot(CompanionMapProtocol.encodeSnapshot(new CompanionMapProtocol.Snapshot(
                token, NativeVersion.dimension(level), entries))).sendTo(player);
    }

    public void move(ServerPlayer player, String payload) {
        if (!ready(player) || !allowMove(player)) return;
        var request = CompanionMapProtocol.decodeMove(payload);
        ServerLevel level = (ServerLevel) player.level();
        if (request == null || !NativeVersion.dimension(level).equals(request.dimension())
                || request.x() == null || request.y() == null || request.z() == null
                || request.ids() == null || request.ids().isEmpty() || request.ids().size() > CompanionMapProtocol.MAX_BATCH) return;
        BlockPos goal = new BlockPos(request.x(), request.y(), request.z());
        if (!validGoal(level, goal)) return;
        Vec3 destination = Vec3.atBottomCenterOf(goal);
        int count = (int) active.values().stream().filter(order -> order.player == player).count();
        var seen = new HashSet<UUID>();
        for (UUID id : request.ids()) {
            if (id == null || !seen.add(id)) continue;
            Entity entity = level.getEntity(id);
            PetAdapter adapter = eligible(player, entity);
            if (adapter == null || !inRange(entity, destination) || runtime.recalls.isActive(id)) continue;
            Order previous = active.get(id);
            boolean replaces = previous != null && previous.player == player;
            if (!replaces && count >= CompanionMapProtocol.MAX_BATCH) continue;
            if (replaces && previous.entity == entity && previous.adapter == adapter && previous.goal.equals(goal)) continue;
            cancel(id);
            if (!detach(entity, player)) {
                if (replaces) count--;
                continue;
            }
            adapter.stand(entity);
            var modes = adapter.mapTravelModes(entity);
            if (modes.isEmpty()) {
                if (replaces) count--;
                continue;
            }
            long deadline = runtime.gameTime() + ORDER_LIFETIME;
            var movement = new MapMove(entity, adapter, modes, destination,
                    () -> validOrder(player, level, goal, entity, adapter, deadline));
            active.put(id, new Order(player, goal, entity, adapter, movement));
            if (!replaces) count++;
        }
    }

    public void tick() {
        if (active.isEmpty()) return;
        for (Order order : new ArrayList<>(active.values())) {
            Entity entity = order.entity;
            UUID id = entity.getUUID();
            if (active.get(id) != order) continue;
            if (!order.movement.tick() && active.remove(id, order)) {
                order.movement.close();
            }
        }
    }

    public void pruneRequests() {
        listRequests.keySet().removeIf(id -> runtime.server.getPlayerList().getPlayer(id) == null);
        moveRequests.keySet().removeIf(id -> runtime.server.getPlayerList().getPlayer(id) == null);
    }

    public void cancel(UUID pet) {
        Order order = active.remove(pet);
        if (order != null) order.movement.close();
    }

    public void close() {
        active.values().forEach(order -> order.movement.close());
        active.clear();
    }

    private boolean validOrder(ServerPlayer player, ServerLevel level, BlockPos goal, Entity entity,
                               PetAdapter adapter, long deadline) {
        UUID id = entity.getUUID();
        return runtime.gameTime() < deadline && ready(player) && player.level() == level
                && level.getEntity(id) == entity && eligible(player, entity) == adapter
                && !runtime.recalls.isActive(id) && validGoal(level, goal);
    }

    private PetAdapter eligible(ServerPlayer player, Entity entity) {
        if (entity == null || entity instanceof Player || !entity.isAlive() || entity.isRemoved()
                || entity.level() != player.level() || entity.hasIndirectPassenger(player)
                || entity instanceof Mob mob && mob.isNoAi()
                || !((ServerLevel) player.level()).isPositionEntityTicking(entity.blockPosition())
                || runtime.transfers.locked(entity.getUUID())) return null;
        var record = runtime.index.get(entity.getUUID());
        if (record == null || !record.valid() || !player.getUUID().equals(record.owner())
                || runtime.index.blacklisted(player.getUUID(), entity.getUUID())) return null;
        var adapter = CompanionCallApi.adapter(entity);
        return player.getUUID().equals(adapter.owner(entity)) && !adapter.mapTravelModes(entity).isEmpty() ? adapter : null;
    }

    private boolean ready(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator()
                && runtime.server.getPlayerList().getPlayer(player.getUUID()) == player;
    }

    private boolean allowMove(ServerPlayer player) {
        long now = runtime.gameTime();
        if (now < moveRequests.getOrDefault(player.getUUID(), Long.MIN_VALUE)) return false;
        moveRequests.put(player.getUUID(), now + MOVE_INTERVAL);
        return true;
    }

    private static boolean validGoal(ServerLevel level, BlockPos goal) {
        return level.isInWorldBounds(goal) && level.getWorldBorder().isWithinBounds(new AABB(goal))
                && level.hasChunkAt(goal);
    }

    private static boolean inRange(Entity entity, Vec3 destination) {
        return entity.position().distanceToSqr(destination) <= MAX_RANGE * MAX_RANGE;
    }

    private static boolean detach(Entity entity, ServerPlayer player) {
        RecallPermissions.detachForRecall(entity, player.getUUID());
        return !entity.isPassenger() && !RecallPermissions.carriesOtherPlayer(entity, player.getUUID());
    }
}
