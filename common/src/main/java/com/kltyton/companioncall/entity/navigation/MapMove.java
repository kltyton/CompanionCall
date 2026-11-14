package com.kltyton.companioncall.entity.navigation;

import com.kltyton.companioncall.api.PetAdapter;
import com.kltyton.companioncall.api.TravelMode;
import com.kltyton.companioncall.entity.ownership.RecallPermissions;
import com.kltyton.companioncall.mixin.access.MobGoals;
import com.kltyton.companioncall.navigation.MobDriver;
import com.kltyton.companioncall.navigation.NavigationProfile;
import com.kltyton.companioncall.navigation.NavigationSession;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Owns a fixed route until completion or cancellation, and releases the native goal on exit. */
public final class MapMove {
    private final Entity entity;
    private final PetAdapter adapter;
    private final UUID owner;
    private final Set<TravelMode> modes;
    private final BooleanSupplier allowed;
    private final NavigationSession navigation;
    private final MoveGoal goal;
    private boolean nativeDriving;
    private int lastNativeTick = Integer.MIN_VALUE;

    public MapMove(Entity entity, PetAdapter adapter, Set<TravelMode> modes, Vec3 destination, BooleanSupplier allowed) {
        this.entity = entity;
        this.adapter = adapter;
        this.owner = adapter.owner(entity);
        this.modes = Set.copyOf(modes);
        this.allowed = allowed;
        navigation = new NavigationSession(entity, NavigationProfile.from(entity, modes));
        navigation.requestFixed(destination);
        if (entity instanceof Mob mob) {
            goal = new MoveGoal(mob);
            ((MobGoals) mob).companionCall$goals().addGoal(0, goal);
        } else {
            goal = null;
        }
    }

    public boolean tick() {
        if (!active() || !valid()) return false;
        RecallPermissions.detachForRecall(entity, owner);
        if (entity.isPassenger() || RecallPermissions.carriesOtherPlayer(entity, owner)) return false;
        // An entity controller can disable the mount's MOVE and JUMP goals.
        if (goal == null || entity.getControllingPassenger() != null
                || navigation.fixedRouteMode() == TravelMode.AIR && lastNativeTick == entity.tickCount) {
            navigation.driveFixedEntity();
        }
        return active();
    }

    public void close() {
        if (goal != null) ((MobGoals) entity).companionCall$goals().removeGoal(goal);
        navigation.cancel();
        // A newly mounted player owns the native controls now.
        if (!entity.isAlive() || entity.isRemoved() || entity.isPassenger()
                || entity.getControllingPassenger() instanceof Player) return;
        releaseNativeInput();
    }

    private boolean active() {
        return switch (navigation.status()) {
            case IDLE, SEARCHING, MOVING, WAITING_REGION -> true;
            case ARRIVED, NO_ROUTE_IN_REGION, LIMIT_REACHED, CANCELLED -> false;
        };
    }

    private boolean valid() {
        return allowed.getAsBoolean() && modes.equals(adapter.mapTravelModes(entity));
    }

    private void releaseNativeInput() {
        if (nativeDriving && entity instanceof Mob mob) hold(mob);
        nativeDriving = false;
    }

    private static void hold(Mob mob) {
        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        mob.setYya(0);
        mob.setZza(0);
    }

    private final class MoveGoal extends Goal {
        private final Mob mob;

        MoveGoal(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
        }

        @Override public boolean canUse() {
            return active() && !mob.isPassenger() && mob.getControllingPassenger() == null && valid()
                    && !RecallPermissions.carriesOtherPlayer(mob, owner);
        }

        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public boolean requiresUpdateEveryTick() { return true; }
        @Override public void start() {
            mob.getNavigation().stop();
            nativeDriving = true;
        }
        @Override public void tick() {
            if (!canUse()) return;
            lastNativeTick = mob.tickCount;
            if (navigation.fixedRouteMode() == TravelMode.AIR) {
                hold(mob);
                return;
            }
            double remaining = navigation.remainingRouteDistance();
            double speed = MobDriver.DEFAULT_SPEED * (1 + Math.min(1, Math.max(0, (remaining - 8) / 80)));
            if (!navigation.drive(mob, speed)) hold(mob);
        }
        @Override public void stop() {
            lastNativeTick = Integer.MIN_VALUE;
            if (!mob.isPassenger() && mob.getControllingPassenger() == null) releaseNativeInput();
            else nativeDriving = false;
        }
    }
}
