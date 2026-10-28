package com.kltyton.companioncall.storage.transfer;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraft.world.phys.Vec3;

public final class LegacyTeleportTarget {
    private static final ThreadLocal<PortalInfo> TARGET = new ThreadLocal<>();
    private LegacyTeleportTarget() {}
    public static PortalInfo current() { return TARGET.get(); }
    public static Entity transfer(Entity entity, ServerLevel destination, Vec3 position) {
        if (entity.level() == destination) {
            entity.teleportTo(position.x, position.y, position.z);
            return entity;
        }
        var order = new ArrayList<Entity>();
        var parents = new IdentityHashMap<Entity, Entity>();
        collect(entity, null, order, parents);
        var results = new IdentityHashMap<Entity, Entity>();
        TARGET.set(new PortalInfo(position, Vec3.ZERO, entity.getYRot(), entity.getXRot()));
        try {
            for (var member : order) {
                Entity moved;
                if (member instanceof ServerPlayer player) {
                    player.teleportTo(destination, position.x, position.y, position.z, player.getYRot(), player.getXRot());
                    moved = player;
                } else moved = member.changeDimension(destination);
                if (moved == null) throw new IllegalStateException("Native dimension transfer returned no entity: " + member.getUUID());
                results.put(member, moved);
            }
            for (var member : order) {
                var parent = parents.get(member);
                if (parent != null && !results.get(member).startRiding(results.get(parent), true)) {
                    throw new IllegalStateException("Could not restore native passenger relationship");
                }
            }
            return results.get(entity);
        } finally {
            TARGET.remove();
        }
    }
    private static void collect(Entity entity, Entity parent, List<Entity> order, Map<Entity, Entity> parents) {
        order.add(entity);
        if (parent != null) parents.put(entity, parent);
        for (var passenger : entity.getPassengers()) collect(passenger, entity, order, parents);
    }
}
