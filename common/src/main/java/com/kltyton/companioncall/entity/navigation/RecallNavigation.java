package com.kltyton.companioncall.entity.navigation;

import com.kltyton.companioncall.api.Approach;
import com.kltyton.companioncall.api.CompanionCallApi;
import com.kltyton.companioncall.api.RecallArrival;
import com.kltyton.companioncall.api.RecallPursuit;
import com.kltyton.companioncall.api.TravelMode;
import com.kltyton.companioncall.entity.ownership.RecallPermissions;
import com.kltyton.companioncall.mixin.access.MobGoals;
import com.kltyton.companioncall.mixin.access.EntityPassengers;
import com.kltyton.companioncall.navigation.NavigationProfile;
import com.kltyton.companioncall.navigation.NavigationSession;
import com.kltyton.companioncall.navigation.NavigationStatus;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;

/** Temporarily owns MOVE while a recall is active, preventing competing follow-owner teleports. */
public final class RecallNavigation extends Goal {
    private static final Map<UUID, RecallNavigation> ACTIVE = new HashMap<>();
    private final Mob mob;
    private final ServerPlayer owner;
    private final Set<TravelMode> modes;
    private final NavigationSession navigation;
    private double speed;
    private RecallPursuit pursuit;
    private boolean boarding;
    private boolean running;
    private long lastDrivenTick = Long.MIN_VALUE;

    private RecallNavigation(Mob mob, ServerPlayer owner, double speed, boolean boarding, Set<TravelMode> modes) {
        this.mob = mob;
        this.owner = owner;
        this.speed = speed;
        this.boarding = boarding;
        this.modes = Set.copyOf(modes);
        this.navigation = new NavigationSession(mob, NavigationProfile.from(mob, this.modes));
        setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
    }

    public static Approach approach(Mob mob, ServerPlayer owner, double speed) {
        return approach(mob, owner, speed, null);
    }

    public static Approach approach(Mob mob, ServerPlayer owner, RecallPursuit pursuit) {
        return approach(mob, owner, pursuit.speed(), pursuit);
    }

    private static Approach approach(Mob mob, ServerPlayer owner, double speed, RecallPursuit pursuit) {
        var adapter = CompanionCallApi.adapter(mob);
        if (adapter == null || !owner.getUUID().equals(adapter.owner(mob))
                || !mob.isAlive() || mob.isRemoved() || !owner.isAlive() || owner.isSpectator()
                || mob.level() != owner.level() || mob.isPassenger()
                || RecallPermissions.carriesOtherPlayer(mob, owner.getUUID())) {
            stop(mob);
            return Approach.BLOCKED;
        }
        Set<TravelMode> modes = adapter.travelModes(mob);
        if (modes.isEmpty()) {
            stop(mob);
            return Approach.BLOCKED;
        }
        boolean boarding = !owner.isPassenger() && adapter.canRide(mob, owner)
                && ((EntityPassengers) mob).companionCall$canAddPassenger(owner);
        if (boarding ? RecallArrival.isAtBoardingPosition(mob, owner) : mob.distanceToSqr(owner) <= 6.25) {
            stop(mob);
            return Approach.ARRIVED;
        }
        var goal = ACTIVE.get(mob.getUUID());
        if (goal != null && (goal.mob != mob || goal.owner != owner || !goal.modes.equals(modes))) {
            discard(goal);
            goal = null;
        }
        if (goal == null) {
            goal = new RecallNavigation(mob, owner, speed, boarding, modes);
            ACTIVE.put(mob.getUUID(), goal);
            ((MobGoals) mob).companionCall$goals().addGoal(0, goal);
        }
        goal.boarding = boarding;
        goal.speed = speed;
        goal.pursuit = pursuit;
        if (pursuit == null) goal.navigation.request(goal.destination());
        else goal.navigation.requestPursuit(pursuit, boarding ? adapter.boardingOffset(mob, owner) : Vec3.ZERO, boarding);
        if (goal.navigation.status() == NavigationStatus.NO_ROUTE_IN_REGION) return Approach.UNREACHABLE;
        return failed(goal.navigation.status()) ? Approach.BLOCKED : Approach.MOVING;
    }

    public static void stop(Mob mob) {
        var goal = ACTIVE.get(mob.getUUID());
        if (goal != null && goal.mob == mob) discard(goal);
    }

    public static double remainingRouteDistance(Mob mob) {
        var goal = ACTIVE.get(mob.getUUID());
        return goal != null && goal.mob == mob ? goal.navigation.remainingRouteDistance() : 0;
    }

    private static void discard(RecallNavigation goal) {
        ACTIVE.remove(goal.mob.getUUID(), goal);
        ((MobGoals) goal.mob).companionCall$goals().removeGoal(goal);
        goal.running = false;
        BoardingApproach.clear(goal.mob);
        goal.navigation.cancel();
        goal.mob.getNavigation().stop();
        goal.holdPosition();
    }

    @Override public boolean canUse() { return valid() && !hasArrived() && !failed(navigation.status()); }
    @Override public boolean canContinueToUse() { return canUse(); }
    @Override public boolean requiresUpdateEveryTick() { return true; }
    @Override public void start() {
        mob.getNavigation().stop();
        running = true;
        lastDrivenTick = Long.MIN_VALUE;
    }
    @Override public void tick() { moveToOwner(); }
    @Override public void stop() {
        if (boarding && valid() && hasArrived()) CompanionCallApi.tryBoard(mob, owner);
        running = false;
        BoardingApproach.clear(mob);
        navigation.cancel();
        holdPosition();
    }

    private boolean valid() {
        if (!mob.isAlive() || mob.isRemoved() || !owner.isAlive() || owner.isRemoved()
                || owner.isSpectator() || mob.level() != owner.level() || mob.isPassenger()
                || RecallPermissions.carriesOtherPlayer(mob, owner.getUUID())) return false;
        var adapter = CompanionCallApi.adapter(mob);
        return adapter != null && owner.getUUID().equals(adapter.owner(mob)) && modes.equals(adapter.travelModes(mob));
    }

    private boolean hasArrived() {
        return boarding ? RecallArrival.isAtBoardingPosition(mob, owner) : mob.distanceToSqr(owner) <= 6.25;
    }

    private void moveToOwner() {
        if (!running || mob.level().getGameTime() == lastDrivenTick) return;
        lastDrivenTick = mob.level().getGameTime();
        if (!valid() || hasArrived()) {
            if (boarding && hasArrived()) CompanionCallApi.tryBoard(mob, owner);
            navigation.cancel();
            holdPosition();
            return;
        }
        Vec3 target = destination();
        boolean driven;
        if (pursuit == null) {
            navigation.request(target);
            driven = !failed(navigation.status()) && navigation.drive(mob, speed);
        } else {
            var adapter = CompanionCallApi.adapter(mob);
            Vec3 offset = boarding ? adapter.boardingOffset(mob, owner) : Vec3.ZERO;
            driven = navigation.drivePursuit(mob, pursuit, offset, boarding);
        }
        if (!driven) holdPosition();
        if (failed(navigation.status())) return;
        if (pursuit != null) return;
        if (boarding && mob.distanceToSqr(owner) <= 9 && navigation.canMoveDirectlyTo(target)) {
            BoardingApproach.authorize(mob, owner);
            if (pursuit == null) {
                double dx = owner.getX() - mob.getX();
                double dz = owner.getZ() - mob.getZ();
                double closingSpeed = Math.min(speed, Math.max(.35, Math.sqrt(Math.hypot(dx, dz))));
                mob.getMoveControl().setWantedPosition(owner.getX(), owner.getY(), owner.getZ(), closingSpeed);
            }
        } else BoardingApproach.clear(mob);
    }

    private Vec3 destination() {
        if (pursuit == null) return owner.position();
        var adapter = CompanionCallApi.adapter(mob);
        Vec3 offset = boarding ? adapter.boardingOffset(mob, owner) : Vec3.ZERO;
        return pursuit.targetFor(mob.position(), offset);
    }

    private void holdPosition() {
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        mob.setYya(0);
        mob.setZza(0);
    }

    private static boolean failed(NavigationStatus status) {
        return status == NavigationStatus.NO_ROUTE_IN_REGION || status == NavigationStatus.LIMIT_REACHED
                || status == NavigationStatus.CANCELLED;
    }
}
