package com.kltyton.companioncall.navigation;

import com.kltyton.companioncall.api.TravelMode;
import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.api.CompanionCallApi;
import com.kltyton.companioncall.api.RecallPursuit;
import com.kltyton.companioncall.api.RecallArrival;
import com.kltyton.companioncall.entity.navigation.BoardingApproach;
import com.kltyton.companioncall.entity.navigation.RecallMotionIntent;
import com.kltyton.companioncall.platform.NativeVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One entity's custom navigation request. This class owns request/lifecycle/replanning only;
 * geometry predicates live in {@link NavigationGeometry} and route-following in {@link MobDriver}.
 */
public final class NavigationSession {
    private static final double GOAL_CHANGE_TOLERANCE = 0.75;
    private static final int MAX_PLAN_TICKS = 1200;
    private static final int MAX_REPLANS = 8;
    private static final int VALIDATE_INTERVAL = 5;
    private static final int REGION_RETRY_TICKS = 10;
    private static final int MAX_PREFIX_POINTS = 6;
    private static final double MAX_PREFIX_LENGTH = 6.0;
    private static final double EPS = 1.0E-5;

    private final Entity entity;
    private final ServerLevel level;
    private final MinecraftServer server;
    private final CollisionWorld world;
    private final NavigationGeometry geometry;
    private final MobDriver driver = new MobDriver();
    private final UUID owner;
    private NavigationProfile profile;

    private NavigationStatus status = NavigationStatus.IDLE;
    private Vec3 requestedGoal;
    private Vec3 plannedRequestGoal;
    private Vec3 targetGoal;
    private TravelMode targetMode;
    private NavigationPath path = NavigationPath.empty();
    private NavigationPath searchPrefix = NavigationPath.empty();
    private AStarPlanner search;
    private int replans;
    private long retryTick = -1;
    private long deadlineTick = -1;
    private long nextValidationTick;
    private boolean pendingGoalReplan;
    private boolean closed;
    private long pursuitTick = Long.MIN_VALUE;
    private Vec3 pursuitVelocity = Vec3.ZERO;
    private Vec3 pursuitPosition;
    private Boolean pursuitGrounded;
    private boolean partialGoal;
    private boolean partialExhausted;
    private NavigationStatus partialTerminal;
    private long partialUpdateTick = Long.MIN_VALUE;
    private int sideIndex = -1;
    private int rejectedSides;
    private Vec3 sideAnchor;
    private long sideTargetTick = Long.MIN_VALUE;
    private AABB sidePlayerBox;
    private AABB sideMountBox;
    private Vec3 cachedSideTarget;

    public NavigationSession(Entity entity, NavigationProfile profile) {
        if (entity == null) throw new IllegalArgumentException("Entity is required");
        if (profile == null) throw new IllegalArgumentException("Profile is required");
        if (!(entity.level() instanceof ServerLevel serverLevel)) {
            throw new IllegalArgumentException("NavigationSession requires a server-level entity");
        }
        this.entity = entity;
        this.level = serverLevel;
        this.server = serverLevel.getServer();
        this.world = new CollisionWorld(serverLevel, entity);
        this.geometry = new NavigationGeometry(entity, world);
        this.owner = ownerOf(entity);
        this.profile = profile;
    }

    public Entity entity() { return entity; }
    public NavigationProfile profile() { return profile; }
    public Vec3 goal() { return requestedGoal; }
    public NavigationStatus status() { return status; }

    public double remainingRouteDistance() {
        requireServerThread();
        if (path.isEmpty()) return 0;
        driver.updateCursor(entity.position(), path);
        return Math.max(0, path.length() - driver.routeCursor());
    }

    public String debugSummary() {
        return "status=" + status + ",requested=" + requestedGoal + ",resolved=" + targetGoal
                + ",points=" + path.size() + ",replans=" + replans + ",session=" + System.identityHashCode(this)
                + ",planned=" + plannedRequestGoal + ",pendingReplan=" + pendingGoalReplan
                + ",cursor=" + driver.routeCursor() + ",routeLength=" + path.length();
    }

    /**
     * Starts or keeps a route. Same-goal requests while active only update the exact goal, extend
     * the final segment when clear, or mark a bounded replan at the old endpoint. NO_ROUTE/LIMIT
     * stay terminal for an unchanged goal until {@link #restart()} or a materially different goal.
     * ARRIVED is re-armed when the entity leaves the arrival hold radius.
     */
    public void request(Vec3 goal) {
        requireServerThread();
        if (goal == null) throw new IllegalArgumentException("Goal is required");
        if (!Double.isFinite(goal.x) || !Double.isFinite(goal.y) || !Double.isFinite(goal.z)) {
            throw new IllegalArgumentException("Goal must be finite");
        }
        if (closed) throw new IllegalStateException("Navigation session is closed");
        long tick = server.getTickCount();
        if (!tickLifecycle(tick)) return;

        profile = profile.adaptTo(entity);
        if ((requestedGoal == null || requestedGoal.distanceToSqr(goal) > EPS || path.isEmpty())
                && directRequest(goal, tick)) return;

        boolean sameGoal = requestedGoal != null
                && requestedGoal.distanceToSqr(goal)
                <= GOAL_CHANGE_TOLERANCE * GOAL_CHANGE_TOLERANCE;
        if (status == NavigationStatus.SEARCHING
                || status == NavigationStatus.MOVING || status == NavigationStatus.WAITING_REGION) {
            mergeRequest(goal, tick);
            return;
        }
        if (sameGoal && (status == NavigationStatus.NO_ROUTE_IN_REGION
                || status == NavigationStatus.LIMIT_REACHED)) {
            return;
        }
        if (sameGoal && status == NavigationStatus.ARRIVED
                && requestedGoal.distanceToSqr(goal) <= EPS
                && entity.position().distanceToSqr(goal)
                <= NavigationGeometry.arrivalRadius(targetModeOrPrimary())
                * NavigationGeometry.arrivalRadius(targetModeOrPrimary())) {
            return;
        }
        profile = profile.adaptTo(entity);
        startRequest(goal, tick);
    }

    public void restart() {
        requireServerThread();
        if (closed) throw new IllegalStateException("Navigation session is closed");
        if (requestedGoal == null) {
            status = NavigationStatus.IDLE;
            clearPlanning();
            return;
        }
        long tick = server.getTickCount();
        if (!tickLifecycle(tick)) return;
        profile = profile.adaptTo(entity);
        startRequest(requestedGoal, tick);
    }

    private void startRequest(Vec3 goal, long tick) {
        requestedGoal = goal;
        plannedRequestGoal = null;
        targetGoal = null;
        targetMode = null;
        path = NavigationPath.empty();
        searchPrefix = NavigationPath.empty();
        search = null;
        replans = 0;
        retryTick = -1;
        deadlineTick = -1;
        nextValidationTick = tick;
        pendingGoalReplan = false;
        partialGoal = false;
        partialExhausted = false;
        driver.clear();
        if (tryArrive(goal, tick)) return;
        status = NavigationStatus.SEARCHING;
        NavigationScheduler.wake(this);
    }

    private boolean directRequest(Vec3 goal, long tick) {
        if (!geometry.canMoveDirectlyTo(profile, goal)) return false;
        if (tryArrive(goal, tick)) return true;
        TravelMode mode = geometry.exactTargetMode(profile, goal);
        NavigationPath.Kind kind = NavigationGeometry.kindForMode(profile, entity.position(), goal, mode);
        if (kind == null) return false;
        requestedGoal = goal;
        plannedRequestGoal = goal;
        targetGoal = goal;
        targetMode = mode;
        search = null;
        searchPrefix = NavigationPath.empty();
        path = NavigationPath.fromPoints(List.of(
                new NavigationPath.Point(entity.position(), NavigationPath.Kind.START),
                new NavigationPath.Point(goal, kind)));
        pendingGoalReplan = false;
        partialGoal = false;
        partialExhausted = false;
        deadlineTick = -1;
        retryTick = -1;
        status = NavigationStatus.MOVING;
        driver.pathChanged(this, tick);
        nextValidationTick = tick;
        NavigationScheduler.forget(this);
        return true;
    }

    private void mergeRequest(Vec3 goal, long tick) {
        requestedGoal = goal;
        if (tryArrive(goal, tick)) return;
        if (status == NavigationStatus.MOVING && !path.isEmpty()) {
            driver.updateCursor(entity.position(), path);
            if (path.length() - driver.routeCursor() <= 1.5 && tryExtend(goal, tick)) return;
        }
        boolean targetMoved = plannedRequestGoal == null || plannedRequestGoal.distanceToSqr(goal)
                > GOAL_CHANGE_TOLERANCE * GOAL_CHANGE_TOLERANCE;
        if (!targetMoved) {
            pendingGoalReplan = false;
            return;
        }
        if ((status == NavigationStatus.SEARCHING
                || status == NavigationStatus.WAITING_REGION) && search == null) {
            pendingGoalReplan = false;
            return;
        }
        if (status == NavigationStatus.MOVING && !path.isEmpty()) {
            pendingGoalReplan = false;
            beginReplan(tick, false);
            return;
        }
        pendingGoalReplan = true;
    }

    private boolean tryArrive(Vec3 requested, long tick) {
        if (requested == null) return false;
        world.beginStep();
        NavigationGeometry.Resolution resolution = geometry.resolveGoal(profile, requested);
        if (resolution.status != CollisionWorld.Tri.CLEAR || resolution.point == null
                || resolution.mode == null) return false;
        double arrival = NavigationGeometry.arrivalRadius(resolution.mode);
        if (requested.distanceToSqr(resolution.point) > arrival * arrival) return false;
        if (entity.position().distanceToSqr(resolution.point) > arrival * arrival) return false;
        if (geometry.arrivalClear(profile, entity.position(), resolution.point, resolution.mode)
                != CollisionWorld.Tri.CLEAR) return false;
        requestedGoal = requested;
        targetGoal = resolution.point;
        targetMode = resolution.mode;
        status = NavigationStatus.ARRIVED;
        clearPlanning();
        NavigationScheduler.forget(this);
        return true;
    }

    private boolean tryExtend(Vec3 goal, long tick) {
        if (path.isEmpty()) return false;
        world.beginStep();
        NavigationGeometry.Resolution resolution = geometry.resolveGoal(profile, goal);
        if (resolution.status != CollisionWorld.Tri.CLEAR || resolution.point == null
                || resolution.mode == null) return false;
        Vec3 end = path.end();
        if (end.distanceToSqr(resolution.point) <= EPS) {
            targetGoal = resolution.point;
            plannedRequestGoal = goal;
            targetMode = resolution.mode;
            pendingGoalReplan = false;
            return true;
        }
        if (end.distanceTo(resolution.point) > 3.0) return false;
        NavigationPath.Kind kind = NavigationGeometry.kindForMode(profile, end, resolution.point,
                resolution.mode);
        if (kind == null || geometry.validateDirect(profile, end, resolution.point, kind)
                != CollisionWorld.Tri.CLEAR) return false;
        NavigationPath tail = NavigationPath.fromPoints(Collections.singletonList(
                new NavigationPath.Point(resolution.point, kind)));
        path = path.append(tail);
        targetGoal = resolution.point;
        plannedRequestGoal = goal;
        targetMode = resolution.mode;
        pendingGoalReplan = false;
        driver.updateCursor(entity.position(), path);
        driver.progressChanged(this, tick);
        return true;
    }

    /**
     * Final-approach predicate for native MoveControl. Validates the original endpoint exactly
     * (no adjacent snap) with the same complete AABB sweep, support, fluid and mode rules.
     */
    public boolean canMoveDirectlyTo(Vec3 target) {
        requireServerThread();
        if (closed || target == null) return false;
        if (!entity.isAlive() || entity.isRemoved()) return false;
        if (entity.level() != level || !entity.level().dimension().equals(level.dimension())) {
            return false;
        }
        if (!Objects.equals(ownerOf(entity), owner)) return false;
        return geometry.canMoveDirectlyTo(profile, target);
    }

    public Vec3 waypoint() {
        if (path.isEmpty() || terminal(status)) return null;
        return driver.waypoint(this);
    }

    public List<Vec3> waypoints() {
        if (path.isEmpty()) return Collections.emptyList();
        return driver.waypoints(this);
    }

    public boolean advanceAlongPath() {
        if (entity instanceof Mob mob) return drive(mob, 1.0);
        if (requestedGoal == null) return false;
        return drivePursuit(entity, new RecallPursuit(requestedGoal, requestedGoal, Vec3.ZERO, 1, 0, false, entity.onGround()));
    }

    public boolean drivePursuit(Entity actor, RecallPursuit pursuit, Vec3 riderOffset) {
        return drivePursuit(actor, pursuit, riderOffset, false);
    }

    public boolean drivePursuit(Entity actor, RecallPursuit pursuit, Vec3 riderOffset, boolean boarding) {
        var runtime = CompanionCall.activeServer(server);
        if (runtime != null) pursuit = runtime.recalls.refreshPursuit(actor, pursuit);
        requestPursuit(pursuit, riderOffset, boarding);
        return followPursuit(actor, pursuit, riderOffset, boarding);
    }

    public void requestPursuit(RecallPursuit pursuit, Vec3 riderOffset) {
        requestPursuit(pursuit, riderOffset, false);
    }

    public void requestPursuit(RecallPursuit pursuit, Vec3 riderOffset, boolean boarding) {
        boolean landed = Boolean.FALSE.equals(pursuitGrounded) && pursuit.grounded();
        pursuitGrounded = pursuit.grounded();
        var player = owner == null ? null : server.getPlayerList().getPlayer(owner);
        boolean sideApproach = boarding && pursuit.grounded() && player != null
                && entity.distanceToSqr(player) <= 64;
        world.approachObstacle(sideApproach ? player : null);
        if (sideApproach) {
            if (sideAnchor == null || sideAnchor.distanceToSqr(player.position()) > .5625) {
                sideAnchor = player.position();
                rejectedSides = 0;
                sideTargetTick = Long.MIN_VALUE;
            }
            if (status == NavigationStatus.NO_ROUTE_IN_REGION && sideIndex >= 0) {
                rejectedSides |= 1 << sideIndex;
                sideIndex = -1;
                sideTargetTick = Long.MIN_VALUE;
            }
        } else {
            sideIndex = -1;
            rejectedSides = 0;
            sideAnchor = null;
            sideTargetTick = Long.MIN_VALUE;
        }
        Vec3 target = sideApproach ? sideTarget(player) : null;
        if (sideApproach && target == null) {
            if (rejectedSides == 15) terminate(NavigationStatus.NO_ROUTE_IN_REGION);
            else {
                status = NavigationStatus.WAITING_REGION;
                clearPlanning();
                NavigationScheduler.forget(this);
            }
            return;
        }
        request(sideApproach ? target.add(pursuit.target().subtract(pursuit.position()))
                : pursuit.targetFor(entity.position(), riderOffset));
        if (landed && pendingGoalReplan) restart();
    }

    private Vec3 sideTarget(ServerPlayer player) {
        profile = profile.adaptTo(entity);
        var playerBox = player.getBoundingBox();
        var mountBox = entity.getBoundingBox();
        long tick = server.getTickCount();
        if (sideTargetTick == tick && playerBox.equals(sidePlayerBox) && mountBox.equals(sideMountBox)) return cachedSideTarget;
        boolean overlap = mountBox.maxX > playerBox.minX && mountBox.minX < playerBox.maxX
                && mountBox.maxZ > playerBox.minZ && mountBox.minZ < playerBox.maxZ;
        boolean clearAbove = overlap && mountBox.minY > playerBox.minY + .05;
        double y = clearAbove ? entity.getY() : playerBox.minY - profile.localMinY();
        double gap = clearAbove ? .05 : -.0005;
        Vec3[] candidates = {
                new Vec3(playerBox.maxX - profile.localMinX() + gap, y, player.getZ()),
                new Vec3(playerBox.minX - profile.localMaxX() - gap, y, player.getZ()),
                new Vec3(player.getX(), y, playerBox.maxZ - profile.localMinZ() + gap),
                new Vec3(player.getX(), y, playerBox.minZ - profile.localMaxZ() - gap)
        };
        Vec3 nearest = null;
        double distance = Double.POSITIVE_INFINITY;
        world.beginStep();
        if (sideIndex >= 0 && (rejectedSides & 1 << sideIndex) == 0
                && geometry.exactTargetMode(profile, candidates[sideIndex]) != null) {
            nearest = candidates[sideIndex];
        }
        boolean direct = false;
        if (nearest == null) for (int i = 0; i < candidates.length; i++) {
            if ((rejectedSides & 1 << i) != 0) continue;
            Vec3 candidate = candidates[i];
            double candidateDistance = entity.position().distanceToSqr(candidate);
            if (geometry.exactTargetMode(profile, candidate) == null) continue;
            boolean candidateDirect = geometry.canMoveDirectlyTo(profile, candidate);
            if ((candidateDirect && !direct) || (candidateDirect == direct && candidateDistance < distance)) {
                nearest = candidate;
                distance = candidateDistance;
                direct = candidateDirect;
                sideIndex = i;
            }
        }
        sideTargetTick = tick;
        sidePlayerBox = playerBox;
        sideMountBox = mountBox;
        cachedSideTarget = nearest;
        return nearest;
    }

    /** Drives an entity through native collision movement at a physical pursuit speed. */
    public boolean drivePursuit(Entity actor, RecallPursuit pursuit) {
        return followPursuit(actor, pursuit, Vec3.ZERO, false);
    }

    private boolean followPursuit(Entity actor, RecallPursuit pursuit, Vec3 riderOffset, boolean boarding) {
        requireServerThread();
        if (actor != entity) throw new IllegalArgumentException("This session belongs to another entity");
        if (!tickLifecycle(server.getTickCount()) || closed || status == NavigationStatus.CANCELLED) return holdPursuit(actor);
        if (pursuitTick == server.getTickCount()) return true;
        Vec3 position = actor.position();
        Vec3 previousMotion = pursuitPosition != null && pursuitTick == server.getTickCount() - 1
                ? position.subtract(pursuitPosition) : actor.getDeltaMovement();
        Vec3 relative = previousMotion.subtract(pursuit.velocity());
        var player = owner == null ? null : server.getPlayerList().getPlayer(owner);
        boolean sideApproach = boarding && pursuit.grounded() && player != null
                && actor.distanceToSqr(player) <= 64;
        Vec3 currentTarget = sideApproach ? sideTarget(player) : pursuit.currentTargetFor(riderOffset);
        if (currentTarget == null) return holdPursuit(actor);
        double captureRange = Math.max(pursuit.speed() * 2, relative.lengthSqr() / (2 * pursuit.accelerationLimit()));
        boolean finalApproach = position.distanceTo(currentTarget) <= captureRange && canMoveDirectlyTo(currentTarget);
        if (finalApproach && (requestedGoal == null || requestedGoal.distanceToSqr(currentTarget) > EPS)) request(currentTarget);
        if (status == NavigationStatus.MOVING) validateAhead(server.getTickCount(), false);
        if (partialExhausted && !path.isEmpty()
                && position.distanceToSqr(path.end()) <= .25) {
            terminate(partialTerminal);
        }
        if (status == NavigationStatus.LIMIT_REACHED || status == NavigationStatus.NO_ROUTE_IN_REGION) return holdPursuit(actor);
        Vec3 steering = finalApproach ? currentTarget : status == NavigationStatus.ARRIVED && requestedGoal != null
                && canMoveDirectlyTo(requestedGoal) ? requestedGoal : driver.pursuitWaypoint(this, pursuit.speed());
        boolean localPrefix = false;
        if (steering == null && (status == NavigationStatus.SEARCHING || status == NavigationStatus.WAITING_REGION)) {
            steering = pursuitPrefix(pursuit);
            localPrefix = steering != null;
        }
        if (steering == null) return holdPursuit(actor);
        if (player != null && actor.distanceToSqr(player) <= 9 && canMoveDirectlyTo(currentTarget)
                && (!sideApproach || finalApproach && RecallArrival.sideReady(actor, player))) BoardingApproach.authorize(actor, player);
        else BoardingApproach.clear(actor);
        Vec3 motion = pursuit.velocityTowardPoint(position, steering, finalApproach);
        TravelMode mode = localPrefix || finalApproach ? geometry.exactTargetMode(profile, steering) : driver.movementMode(this);
        double cap = Math.min(pursuit.speed(), mode == TravelMode.LAND ? 2.25 : pursuit.speed());
        double distance = position.distanceTo(steering);
        if (!finalApproach) cap = Math.min(cap, distance);
        if (motion.length() > cap) motion = motion.scale(cap / motion.length());
        if (mode == TravelMode.LAND) motion = new Vec3(motion.x, actor.getDeltaMovement().y, motion.z);
        if (mode == TravelMode.LAND) {
            previousMotion = new Vec3(previousMotion.x, actor.getDeltaMovement().y, previousMotion.z);
        }
        Vec3 change = motion.subtract(previousMotion);
        double accelerationLimit = pursuit.accelerationLimit();
        if (change.length() > accelerationLimit) motion = previousMotion.add(change.normalize().scale(accelerationLimit));
        world.beginStep();
        if (world.segmentClear(position, position.add(motion), profile) == CollisionWorld.Tri.UNKNOWN) return holdPursuit(actor);
        pursuitTick = server.getTickCount();
        pursuitPosition = position;
        NativeVersion.synchronizeMovement(actor);
        var runtime = CompanionCall.activeServer(server);
        if (runtime != null) runtime.recalls.sharedMotionPrepared(actor);
        if (actor instanceof Mob mob) {
            double physicalSpeed = mode == TravelMode.LAND
                    ? Math.sqrt(motion.x * motion.x + motion.z * motion.z) : motion.length();
            double multiplier = pursuit.nativeMultiplier(mob, physicalSpeed);
            RecallMotionIntent.prepare(mob, steering, multiplier, mode);
            if (mode == TravelMode.LAND && mob.onGround() && steering.y > position.y + mob.maxUpStep()) {
                mob.getJumpControl().jump();
            }
            mob.setDeltaMovement(motion);
        } else {
            actor.setDeltaMovement(Vec3.ZERO);
            actor.move(MoverType.SELF, motion);
            pursuitVelocity = actor.position().subtract(position);
            if (player != null && player.getRootVehicle() == actor) {
                actor.setDeltaMovement(pursuitVelocity);
                player.setDeltaMovement(pursuitVelocity);
                actor.resetFallDistance();
                player.resetFallDistance();
            }
        }
        return true;
    }

    private boolean holdPursuit(Entity actor) {
        pursuitVelocity = Vec3.ZERO;
        BoardingApproach.clear(actor);
        var player = owner == null ? null : server.getPlayerList().getPlayer(owner);
        if (player == null || player.getRootVehicle() != actor) {
            actor.setDeltaMovement(actor.isNoGravity() ? Vec3.ZERO : new Vec3(0, actor.getDeltaMovement().y, 0));
        }
        if (actor instanceof Mob mob) {
            mob.getNavigation().stop();
            mob.getMoveControl().setWantedPosition(actor.getX(), actor.getY(), actor.getZ(), 0);
            mob.setZza(0);
            mob.setYya(0);
        }
        return false;
    }

    /** Advances only through an already clear local segment while the complete route is pending. */
    private Vec3 pursuitPrefix(RecallPursuit pursuit) {
        if (requestedGoal == null) return null;
        Vec3 position = entity.position();
        Vec3 direction = requestedGoal.subtract(position);
        double distance = direction.length();
        if (distance <= EPS) return null;
        Vec3 candidate = position.add(direction.scale(Math.min(distance, pursuit.speed()) / distance));
        if (!canMoveDirectlyTo(candidate)) {
            if (!profile.allows(TravelMode.AIR)) return null;
            Vec3 horizontal = new Vec3(direction.x, 0, direction.z);
            double horizontalDistance = horizontal.length();
            Vec3 across = horizontalDistance <= EPS ? position
                    : position.add(horizontal.scale(Math.min(horizontalDistance, pursuit.speed()) / horizontalDistance));
            if (horizontalDistance > EPS && canMoveDirectlyTo(across)) candidate = across;
            else {
                candidate = position.add(0, pursuit.speed(), 0);
                if (!canMoveDirectlyTo(candidate)) return null;
            }
        }
        return candidate;
    }

    public boolean drive(Mob mob, double speed) {
        requireServerThread();
        if (mob != entity) throw new IllegalArgumentException("This session belongs to another entity");
        long tick = server.getTickCount();
        if (!tickLifecycle(tick)) return false;
        if (closed || status == NavigationStatus.CANCELLED
                || status == NavigationStatus.LIMIT_REACHED
                || status == NavigationStatus.NO_ROUTE_IN_REGION) return false;

        NavigationProfile adapted = profile.adaptTo(entity);
        if (!profile.sameAs(adapted)) {
            profile = adapted;
            search = null;
            path = NavigationPath.empty();
            searchPrefix = NavigationPath.empty();
            pendingGoalReplan = false;
            driver.clear();
            if (status == NavigationStatus.MOVING) {
                beginReplan(tick);
            } else if (status == NavigationStatus.SEARCHING
                    || status == NavigationStatus.WAITING_REGION) {
                status = NavigationStatus.SEARCHING;
                retryTick = 0;
            }
        }
        if (status == NavigationStatus.ARRIVED) {
            if (requestedGoal == null) {
                status = NavigationStatus.IDLE;
                return false;
            }
            double hold = NavigationGeometry.arrivalHoldRadius(targetModeOrPrimary());
            if (entity.position().distanceToSqr(requestedGoal) <= hold * hold) return false;
            if (canMoveDirectlyTo(requestedGoal)) return driver.driveDirect(this, mob, speed);
            startRequest(requestedGoal, tick);
            return false;
        }
        world.beginStep();
        if (status == NavigationStatus.MOVING) {
            validateAhead(tick, false);
            if (status == NavigationStatus.CANCELLED || status == NavigationStatus.LIMIT_REACHED
                    || status == NavigationStatus.NO_ROUTE_IN_REGION) return false;
            if (status == NavigationStatus.WAITING_REGION) return false;
        } else if (status == NavigationStatus.WAITING_REGION && search == null
                && !path.isEmpty()) {
            validateAhead(tick, true);
            if (status == NavigationStatus.WAITING_REGION) return false;
        }
        if (path.isEmpty()) {
            if (status == NavigationStatus.MOVING) beginReplan(tick);
            return false;
        }
        return driver.followRoute(this, mob, speed);
    }

    public void cancel() {
        requireServerThread();
        terminate(NavigationStatus.CANCELLED);
        if (pursuitTick != Long.MIN_VALUE) holdPursuit(entity);
    }

    int planTurn(int maxExpansions, long tick) {
        if (closed || status == NavigationStatus.CANCELLED) return 0;
        if (!tickLifecycle(tick)) return 0;
        if (status != NavigationStatus.SEARCHING && status != NavigationStatus.WAITING_REGION) {
            return 0;
        }
        if (status == NavigationStatus.WAITING_REGION) {
            if (search == null && !path.isEmpty() && !partialGoal) return 0;
            if (tick < retryTick) return 0;
            search = null;
        }
        if (deadlineTick < 0) deadlineTick = tick + MAX_PLAN_TICKS;
        if (tick > deadlineTick) {
            terminate(NavigationStatus.LIMIT_REACHED);
            return 0;
        }
        NavigationProfile adapted = profile.adaptTo(entity);
        if (!profile.sameAs(adapted)) {
            profile = adapted;
            search = null;
            searchPrefix = NavigationPath.empty();
            path = NavigationPath.empty();
            driver.clear();
        }
        if (search == null && !startSearch(tick, !path.isEmpty())) return 0;

        AStarPlanner.Result result = search.step(maxExpansions);
        switch (result.progress) {
            case FOUND -> {
                NavigationPath combined = searchPrefix.isEmpty()
                        ? result.path : searchPrefix.append(result.path);
                path = geometry.smooth(profile, combined);
                search = null;
                searchPrefix = NavigationPath.empty();
                deadlineTick = -1;
                retryTick = -1;
                if (path.isEmpty()) {
                    status = NavigationStatus.ARRIVED;
                    clearPlanning();
                    NavigationScheduler.forget(this);
                } else {
                    status = NavigationStatus.MOVING;
                    driver.pathChanged(this, tick);
                    nextValidationTick = tick;
                    if (pendingGoalReplan) {
                        pendingGoalReplan = false;
                        beginReplan(tick, false);
                    }
                }
                return result.used;
            }
            case EXPANDED -> {
                status = NavigationStatus.SEARCHING;
                if (partialGoal && partialUpdateTick != tick) {
                    partialUpdateTick = tick;
                    updatePartialPath(search.closestPath(), tick);
                }
                return result.used;
            }
            case WAITING_REGION -> {
                status = NavigationStatus.WAITING_REGION;
                if (partialGoal) updatePartialPath(search.closestPath(), tick);
                else path = searchPrefix;
                driver.pathChanged(this, tick);
                retryTick = tick + REGION_RETRY_TICKS;
                return result.used;
            }
            case NO_ROUTE -> {
                if (pendingGoalReplan) startRequest(requestedGoal, tick);
                else if (!finishPartialSearch(tick, NavigationStatus.NO_ROUTE_IN_REGION)) terminate(NavigationStatus.NO_ROUTE_IN_REGION);
                return result.used;
            }
            case LIMITED -> {
                if (pendingGoalReplan) startRequest(requestedGoal, tick);
                else if (!finishPartialSearch(tick, NavigationStatus.LIMIT_REACHED)) terminate(NavigationStatus.LIMIT_REACHED);
                return result.used;
            }
            default -> {
                return result.used;
            }
        }
    }

    boolean isScheduled() {
        return !closed && (status == NavigationStatus.SEARCHING
                || (status == NavigationStatus.WAITING_REGION
                && (search != null || path.isEmpty() || partialGoal)));
    }

    MinecraftServer server() { return server; }

    void closeFromScheduler() {
        closed = true;
        status = NavigationStatus.CANCELLED;
        clearPlanning();
    }

    private boolean startSearch(long tick, boolean preservePrefix) {
        world.beginStep();
        NavigationGeometry.Resolution goal = geometry.resolveGoal(profile, requestedGoal);
        if (goal.status == CollisionWorld.Tri.UNKNOWN) {
            status = NavigationStatus.WAITING_REGION;
            retryTick = tick + REGION_RETRY_TICKS;
            return false;
        }
        NavigationGeometry.Start start = geometry.resolveStart(profile);
        if (start == null) {
            status = NavigationStatus.WAITING_REGION;
            retryTick = tick + REGION_RETRY_TICKS;
            return false;
        }
        if (goal.status != CollisionWorld.Tri.CLEAR || goal.point == null || goal.mode == null) {
            if (pursuitGrounded != null && profile.allows(TravelMode.LAND)
                    && !profile.allows(TravelMode.AIR) && start.mode == TravelMode.LAND) {
                partialGoal = true;
                targetGoal = requestedGoal;
                plannedRequestGoal = requestedGoal;
                targetMode = TravelMode.LAND;
                searchPrefix = NavigationPath.empty();
                search = new AStarPlanner(world, profile, start.point, start.mode, requestedGoal, TravelMode.LAND);
                status = NavigationStatus.SEARCHING;
                retryTick = 0;
                return true;
            }
            terminate(NavigationStatus.NO_ROUTE_IN_REGION);
            return false;
        }
        partialGoal = false;
        partialExhausted = false;
        NavigationPath prefix = preservePrefix ? safePrefix() : NavigationPath.empty();
        Vec3 startPoint = prefix.isEmpty() ? start.point : prefix.end();
        searchPrefix = prefix;
        path = prefix;
        targetGoal = goal.point;
        plannedRequestGoal = requestedGoal;
        targetMode = goal.mode;
        NavigationPath.Kind directKind = start.mode == goal.mode
                ? NavigationGeometry.kindForMode(profile, startPoint, goal.point, goal.mode) : null;
        if (directKind != null && geometry.validateDirect(profile, startPoint, goal.point, directKind)
                == CollisionWorld.Tri.CLEAR) {
            NavigationPath direct = NavigationPath.fromPoints(List.of(
                    new NavigationPath.Point(startPoint, NavigationPath.Kind.START),
                    new NavigationPath.Point(goal.point, directKind)));
            path = prefix.isEmpty() ? direct : prefix.append(direct);
            searchPrefix = NavigationPath.empty();
            status = NavigationStatus.MOVING;
            deadlineTick = -1;
            retryTick = -1;
            driver.pathChanged(this, tick);
            nextValidationTick = tick;
            return false;
        }
        search = new AStarPlanner(world, profile, startPoint, start.mode, goal.point, goal.mode);
        status = NavigationStatus.SEARCHING;
        retryTick = 0;
        driver.pathChanged(this, tick);
        if (deadlineTick < 0) deadlineTick = tick + MAX_PLAN_TICKS;
        return true;
    }

    private void updatePartialPath(NavigationPath candidate, long tick) {
        if (candidate.isEmpty() || (!path.isEmpty() && candidate.end().distanceToSqr(requestedGoal)
                >= path.end().distanceToSqr(requestedGoal))) return;
        path = candidate;
        driver.pathChanged(this, tick);
    }

    private boolean finishPartialSearch(long tick, NavigationStatus terminalStatus) {
        if (pursuitGrounded == null || !profile.allows(TravelMode.LAND) || profile.allows(TravelMode.AIR)) return false;
        NavigationPath closest = search.closestPath();
        if (!closest.isEmpty()) updatePartialPath(searchPrefix.isEmpty() ? closest : searchPrefix.append(closest), tick);
        if (path.isEmpty() || entity.position().distanceToSqr(path.end()) <= .25) return false;
        search = null;
        searchPrefix = NavigationPath.empty();
        partialGoal = true;
        partialExhausted = true;
        partialTerminal = terminalStatus;
        status = NavigationStatus.MOVING;
        nextValidationTick = tick;
        NavigationScheduler.forget(this);
        return true;
    }

    private NavigationPath safePrefix() {
        if (path.isEmpty()) return NavigationPath.empty();
        world.beginStep();
        Vec3 position = entity.position();
        driver.updateCursor(position, path);
        double closest = driver.routeCursor();
        int first = path.size() - 1;
        for (int i = 0; i < path.size(); i++) {
            if (path.distanceAt(i) > closest + 0.2) {
                first = i;
                break;
            }
        }
        List<NavigationPath.Point> points = new ArrayList<>();
        Vec3 previous = position;
        points.add(new NavigationPath.Point(previous, NavigationPath.Kind.START));
        double length = 0.0;
        int limit = Math.min(path.size() - 1, first + MAX_PREFIX_POINTS);
        for (int i = first; i <= limit; i++) {
            Vec3 next = path.points().get(i).feet();
            double step = previous.distanceTo(next);
            if (step > 4.0) break;
            TravelMode mode = i > 0 ? modeOf(path.segments().get(i - 1))
                    : targetModeOrPrimary();
            NavigationPath.Kind kind = NavigationGeometry.kindForMode(profile, previous, next, mode);
            if (kind == null || geometry.validateDirect(profile, previous, next, kind)
                    != CollisionWorld.Tri.CLEAR) break;
            points.add(new NavigationPath.Point(next, kind));
            previous = next;
            length += step;
            if (length >= MAX_PREFIX_LENGTH) break;
        }
        return points.size() > 1 ? NavigationPath.fromPoints(points) : NavigationPath.empty();
    }

    private void validateAhead(long tick, boolean force) {
        if (path.isEmpty()) return;
        if (!force && tick < nextValidationTick) return;
        nextValidationTick = tick + VALIDATE_INTERVAL;
        world.beginStep();
        driver.updateCursor(entity.position(), path);
        int first = driver.cursorSegment();
        int last = Math.min(path.segments().size() - 1, first + 2);
        CollisionWorld.Tri validation = CollisionWorld.Tri.CLEAR;
        for (int i = Math.max(0, first - 1); i <= last; i++) {
            NavigationPath.Segment segment = path.segments().get(i);
            CollisionWorld.Tri result = geometry.validateSegment(profile, path, i, segment);
            if (result == CollisionWorld.Tri.UNKNOWN) {
                validation = CollisionWorld.Tri.UNKNOWN;
                break;
            }
            if (result != CollisionWorld.Tri.CLEAR) {
                validation = CollisionWorld.Tri.BLOCKED;
                break;
            }
        }
        if (validation == CollisionWorld.Tri.UNKNOWN) {
            status = NavigationStatus.WAITING_REGION;
            search = null;
        } else if (validation == CollisionWorld.Tri.BLOCKED) {
            beginReplan(tick);
        } else if (status == NavigationStatus.WAITING_REGION) {
            status = NavigationStatus.MOVING;
        }
    }

    private void beginReplan(long tick) {
        beginReplan(tick, true);
    }

    private void beginReplan(long tick, boolean obstruction) {
        if (closed || status == NavigationStatus.CANCELLED) return;
        if (status == NavigationStatus.SEARCHING
                || (status == NavigationStatus.WAITING_REGION && search != null)) return;
        if (obstruction && replans >= MAX_REPLANS) {
            terminate(NavigationStatus.LIMIT_REACHED);
            return;
        }
        if (obstruction) replans++;
        partialExhausted = false;
        NavigationPath prefix = safePrefix();
        path = prefix;
        searchPrefix = prefix;
        search = null;
        retryTick = 0;
        deadlineTick = tick + MAX_PLAN_TICKS;
        status = NavigationStatus.SEARCHING;
        driver.pathChanged(this, tick);
        NavigationScheduler.wake(this);
    }

    private boolean tickLifecycle(long tick) {
        if (closed) return false;
        if (!entity.isAlive() || entity.isRemoved()) {
            terminate(NavigationStatus.CANCELLED);
            return false;
        }
        if (entity.level() != level || !entity.level().dimension().equals(level.dimension())) {
            terminate(NavigationStatus.CANCELLED);
            return false;
        }
        if (!Objects.equals(ownerOf(entity), owner)) {
            terminate(NavigationStatus.CANCELLED);
            return false;
        }
        if (deadlineTick >= 0 && tick > deadlineTick
                && (status == NavigationStatus.SEARCHING
                || status == NavigationStatus.WAITING_REGION)
                && search != null) {
            terminate(NavigationStatus.LIMIT_REACHED);
            return false;
        }
        return true;
    }

    private void terminate(NavigationStatus next) {
        status = next;
        clearPlanning();
        NavigationScheduler.forget(this);
    }

    private void clearPlanning() {
        path = NavigationPath.empty();
        searchPrefix = NavigationPath.empty();
        search = null;
        retryTick = -1;
        pendingGoalReplan = false;
        driver.clear();
    }

    private TravelMode targetModeOrPrimary() {
        return targetMode != null ? targetMode : profile.primaryMode();
    }

    private static TravelMode modeOf(NavigationPath.Segment segment) {
        if (segment.kind() == NavigationPath.Kind.SWIM) return TravelMode.WATER;
        if (segment.kind() == NavigationPath.Kind.FLY) return TravelMode.AIR;
        return TravelMode.LAND;
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Navigation session must be used on the server thread");
        }
    }

    private static UUID ownerOf(Entity entity) {
        var adapter = CompanionCallApi.adapter(entity);
        return adapter == null ? NativeVersion.owner(entity) : adapter.owner(entity);
    }

    private static boolean terminal(NavigationStatus status) {
        return status == NavigationStatus.ARRIVED || status == NavigationStatus.CANCELLED
                || status == NavigationStatus.LIMIT_REACHED
                || status == NavigationStatus.NO_ROUTE_IN_REGION;
    }

    NavigationPath pathView() { return path; }
    NavigationProfile profileView() { return profile; }
    CollisionWorld worldView() { return world; }
    NavigationGeometry geometryView() { return geometry; }
    Vec3 requestedGoalView() { return requestedGoal; }
    TravelMode targetModeView() { return targetModeOrPrimary(); }
    long serverTick() { return server.getTickCount(); }
    boolean tryArriveInternal(long tick) { return tryArrive(requestedGoal, tick); }
    void rejectEndpointInternal() { terminate(NavigationStatus.NO_ROUTE_IN_REGION); }
    void beginReplanInternal(long tick) { beginReplan(tick); }
}
