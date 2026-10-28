package com.kltyton.companioncall.entity.navigation;

import com.kltyton.companioncall.api.Approach;
import com.kltyton.companioncall.api.CompanionCallApi;
import com.kltyton.companioncall.api.RecallArrival;
import com.kltyton.companioncall.api.RecallPursuit;
import com.kltyton.companioncall.api.TravelMode;
import com.kltyton.companioncall.navigation.NavigationProfile;
import com.kltyton.companioncall.navigation.NavigationSession;
import com.kltyton.companioncall.navigation.NavigationStatus;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/** Owns recall sessions for entities that do not have a vanilla mob goal selector. */
public final class EntityRecallNavigation {
    private record Active(Entity entity, UUID owner, Set<TravelMode> modes, NavigationSession navigation) {}
    private static final Map<UUID, Active> ACTIVE = new HashMap<>();

    private EntityRecallNavigation() {}

    public static Approach approach(Entity entity, ServerPlayer owner, RecallPursuit pursuit) {
        if (entity instanceof Mob mob) return RecallNavigation.approach(mob, owner, pursuit);
        var adapter = CompanionCallApi.adapter(entity);
        boolean boarding = !owner.isPassenger() && adapter.canRide(entity, owner);
        if (boarding ? RecallArrival.isAtBoardingPosition(entity, owner) : entity.distanceToSqr(owner) <= 6.25) {
            CompanionCallApi.tryBoard(entity, owner);
            stop(entity);
            return Approach.ARRIVED;
        }
        Set<TravelMode> modes = adapter.travelModes(entity);
        Active active = ACTIVE.get(entity.getUUID());
        if (active != null && (active.entity != entity || !active.owner.equals(owner.getUUID()) || !active.modes.equals(modes))) {
            stop(active.entity);
            active = null;
        }
        if (active == null) {
            active = new Active(entity, owner.getUUID(), Set.copyOf(modes),
                    new NavigationSession(entity, NavigationProfile.from(entity, modes)));
            ACTIVE.put(entity.getUUID(), active);
        }
        Vec3 offset = boarding ? adapter.boardingOffset(entity, owner) : Vec3.ZERO;
        active.navigation.drivePursuit(entity, pursuit, offset, boarding);
        return switch (active.navigation.status()) {
            case NO_ROUTE_IN_REGION -> Approach.UNREACHABLE;
            case CANCELLED, LIMIT_REACHED -> Approach.BLOCKED;
            default -> Approach.MOVING;
        };
    }

    public static void stop(Entity entity) {
        if (entity instanceof Mob mob) {
            RecallNavigation.stop(mob);
            return;
        }
        Active active = ACTIVE.get(entity.getUUID());
        if (active == null || active.entity != entity) return;
        ACTIVE.remove(entity.getUUID());
        active.navigation.cancel();
        BoardingApproach.clear(entity);
    }

    public static double remainingRouteDistance(Entity entity) {
        if (entity instanceof Mob mob) return RecallNavigation.remainingRouteDistance(mob);
        Active active = ACTIVE.get(entity.getUUID());
        return active != null && active.entity == entity ? active.navigation.remainingRouteDistance() : 0;
    }
}
