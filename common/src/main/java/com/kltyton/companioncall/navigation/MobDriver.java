package com.kltyton.companioncall.navigation;

import com.kltyton.companioncall.api.TravelMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Route-following controller. Owns the monotonic route cursor, progress tracking, lookahead and
 * steering. Native MoveControl/JumpControl/LookControl perform the physical movement.
 */
public final class MobDriver {
    public static final double DEFAULT_SPEED = 1.0;
    private static final int BLOCKED_PROGRESS_TICKS = 40;
    private static final double LAND_WAYPOINT_RADIUS_SQR = .5 * .5;
    private static final double LAND_WAYPOINT_HEIGHT = .35;
    private static final double LAND_CORRIDOR_RADIUS = .70;
    private static final double FLIGHT_LOOKAHEAD = 2.0;
    static final double FLIGHT_WAYPOINT_RADIUS = .35;
    private static final double FLIGHT_WAYPOINT_RADIUS_SQR = FLIGHT_WAYPOINT_RADIUS * FLIGHT_WAYPOINT_RADIUS;
    private static final double EPS = 1.0E-5;

    private int cursorSegment;
    private double routeCursor;
    private double bestRemaining = Double.POSITIVE_INFINITY;
    private long lastProgressTick;
    private Vec3 landLegOrigin;

    enum CursorUpdate {
        TRACKING,
        REPLAN,
        WAITING_REGION
    }

    public static boolean drive(NavigationSession session, Mob mob) {
        return session.drive(mob, DEFAULT_SPEED);
    }

    public static boolean drive(NavigationSession session, Mob mob, double speed) {
        return session.drive(mob, speed);
    }

    public static boolean advance(NavigationSession session) {
        return session.advanceAlongPath();
    }

    void clear() {
        cursorSegment = 0;
        routeCursor = 0.0;
        bestRemaining = Double.POSITIVE_INFINITY;
        lastProgressTick = 0;
        landLegOrigin = null;
    }

    void pathChanged(NavigationSession session, long tick) {
        NavigationPath path = session.pathView();
        cursorSegment = 0;
        routeCursor = 0.0;
        lastProgressTick = tick;
        if (path.isEmpty() || path.segments().isEmpty()) {
            bestRemaining = 0.0;
            return;
        }
        Vec3 position = session.entity().position();
        landLegOrigin = position;
        int limit = path.segments().get(0).kind() == NavigationPath.Kind.FLY
                ? 0 : Math.min(path.segments().size() - 1, 8);
        double bestDistance = Double.POSITIVE_INFINITY;
        int bestSegment = 0;
        double bestRoute = 0.0;
        for (int i = 0; i <= limit; i++) {
            NavigationPath.Segment segment = path.segments().get(i);
            NavigationPath.Segment.Projection projection = project(position, segment);
            double distance = position.distanceToSqr(projection.point());
            if (distance < bestDistance) {
                bestDistance = distance;
                bestSegment = i;
                bestRoute = path.distanceAt(i) + projection.distance();
            }
        }
        cursorSegment = bestSegment;
        routeCursor = bestRoute;
        bestRemaining = Math.max(0.0, path.length() - routeCursor);
    }

    void updateCursor(Vec3 position, NavigationPath path) {
        if (path.isEmpty() || path.segments().isEmpty()) {
            cursorSegment = 0;
            routeCursor = 0.0;
            return;
        }
        List<NavigationPath.Segment> segments = path.segments();
        cursorSegment = Math.min(Math.max(cursorSegment, 0), segments.size() - 1);
        NavigationPath.Segment segment = segments.get(cursorSegment);
        NavigationPath.Segment.Projection projection = project(position, segment);
        double candidate = path.distanceAt(cursorSegment) + projection.distance();
        if (candidate > routeCursor) routeCursor = candidate;
        for (int advance = 0; advance < 2 && segment.kind() == NavigationPath.Kind.SWIM
                && cursorSegment < segments.size() - 1; advance++) {
            double end = path.distanceAt(cursorSegment + 1);
            if (routeCursor < end - .2 || position.distanceToSqr(segment.to()) > 1.0) break;
            cursorSegment++;
            segment = segments.get(cursorSegment);
        }
    }

    CursorUpdate updateLandCursor(NavigationSession session, Vec3 position) {
        NavigationPath path = session.pathView();
        updateCursor(position, path);
        if (path.segments().isEmpty()) return CursorUpdate.TRACKING;
        NavigationPath.Segment segment = currentSegment(path);
        if (!landSegment(segment)) return CursorUpdate.TRACKING;
        if (!session.entity().onGround()) return CursorUpdate.TRACKING;

        List<NavigationPath.Segment> segments = path.segments();
        int advances = 0;
        while (advances++ < 2) {
            segment = currentSegment(path);
            if (!landSegment(segment)) return CursorUpdate.TRACKING;
            boolean atWaypoint = reachedLandWaypoint(position, segment.to());
            boolean passed = passedLandWaypoint(position, segment);
            if (!atWaypoint && !passed) return CursorUpdate.TRACKING;
            if (cursorSegment >= segments.size() - 1) {
                return passed && !atWaypoint ? CursorUpdate.REPLAN : CursorUpdate.TRACKING;
            }
            if (passed && offLandLeg(position, segment)) return CursorUpdate.REPLAN;

            NavigationPath.Segment following = segments.get(cursorSegment + 1);
            CollisionWorld.Tri join = joinFollowingLandSegment(session, position, following);
            if (join == CollisionWorld.Tri.UNKNOWN) return CursorUpdate.WAITING_REGION;
            if (join != CollisionWorld.Tri.CLEAR) return CursorUpdate.REPLAN;

            cursorSegment++;
            routeCursor = Math.max(routeCursor, path.distanceAt(cursorSegment));
            landLegOrigin = position;
        }
        return CursorUpdate.TRACKING;
    }

    double routeCursor() { return routeCursor; }
    int cursorSegment() { return cursorSegment; }

    void advanceFlightWaypoint(Vec3 position, NavigationPath path) {
        cursorSegment++;
        routeCursor = path.distanceAt(cursorSegment);
        updateCursor(position, path);
    }

    static boolean reachedFlightWaypoint(Vec3 position, Vec3 waypoint) {
        return position.distanceToSqr(waypoint) <= FLIGHT_WAYPOINT_RADIUS_SQR;
    }

    boolean isFinalFlightLeg(NavigationPath path) {
        return !path.segments().isEmpty() && cursorSegment == path.segments().size() - 1;
    }

    boolean outsideFlightCorridor(Vec3 position, NavigationPath path) {
        NavigationPath.Segment segment = currentSegment(path);
        return segment != null && position.distanceToSqr(project(position, segment).point()) > 4.0;
    }

    void progressChanged(NavigationSession session, long tick) {
        bestRemaining = Math.max(0.0, session.pathView().length() - routeCursor);
        lastProgressTick = tick;
    }

    Vec3 waypoint(NavigationSession session) {
        NavigationPath path = session.pathView();
        if (path.isEmpty()) return null;
        updateCursor(session.entity().position(), path);
        NavigationPath.Segment segment = currentSegment(path);
        if (segment != null && segment.kind() == NavigationPath.Kind.FLY) return segment.to();
        if (landSegment(segment)) return forwardLandAim(session.entity().position(), segment,
                lookahead(segment));
        double lookahead = lookahead(segment);
        return path.pointAt(Math.min(path.length(), routeCursor + lookahead));
    }

    List<Vec3> waypoints(NavigationSession session) {
        NavigationPath path = session.pathView();
        if (path.isEmpty()) return Collections.emptyList();
        updateCursor(session.entity().position(), path);
        int start = Math.min(path.size() - 1, cursorSegment + 1);
        List<Vec3> result = new ArrayList<>();
        for (int i = start; i < path.size(); i++) result.add(path.points().get(i).feet());
        return Collections.unmodifiableList(result);
    }

    Vec3 pursuitWaypoint(NavigationSession session, double speed) {
        NavigationPath path = session.pathView();
        if (path.isEmpty() || path.segments().isEmpty()) return null;
        Vec3 position = session.entity().position();
        updateCursor(position, path);
        NavigationPath.Segment segment = currentSegment(path);
        Vec3 desired = segment != null && segment.kind() == NavigationPath.Kind.FLY ? segment.to()
                : landSegment(segment) ? forwardLandAim(position, segment, Math.max(2, speed * 2))
                : path.pointAt(Math.min(path.length(), routeCursor + Math.max(2, speed * 2)));
        // The session validates the active flight leg. Pursuit also sweeps the actual movement.
        if (segment != null && segment.kind() == NavigationPath.Kind.FLY) return desired;
        return desired == null ? null : steeringTarget(session.worldView(), session.geometryView(),
                session.profileView(), position, desired, segment);
    }

    TravelMode movementMode(NavigationSession session) {
        return session.pathView().isEmpty() ? session.targetModeView()
                : segmentMode(currentSegment(session.pathView()), session.profileView());
    }

    boolean followRoute(NavigationSession session, Mob mob, double speed) {
        NavigationPath path = session.pathView();
        NavigationProfile profile = session.profileView();
        CollisionWorld world = session.worldView();
        NavigationGeometry geometry = session.geometryView();
        if (path.isEmpty()) return false;
        Vec3 position = mob.position();
        long tick = session.serverTick();
        CursorUpdate cursor = updateLandCursor(session, position);
        if (cursor != CursorUpdate.TRACKING) {
            session.handleGroundCursor(cursor, tick);
            brake(mob, position);
            return false;
        }
        NavigationPath.Segment segment = currentSegment(path);
        boolean flight = segment != null && segment.kind() == NavigationPath.Kind.FLY;

        Vec3 requested = session.routeGoalView();
        if (requested != null && (!flight || cursorSegment == path.segments().size() - 1
                && reachedFlightWaypoint(position, path.end()))) {
            double arrival = NavigationGeometry.arrivalRadius(session.targetModeView());
            if (position.distanceToSqr(requested) <= arrival * arrival
                    && session.tryArriveInternal(tick)) {
                return false;
            }
        }
        double remaining = Math.max(0.0, path.length() - routeCursor);
        boolean atEnd = flight ? cursorSegment == path.segments().size() - 1
                && reachedFlightWaypoint(position, path.end())
                : mob.onGround() && (remaining <= 0.15 || position.distanceToSqr(path.end()) <= 0.25);
        if (atEnd && session.awaitingContinuation()) {
            brake(mob, position);
            return false;
        }
        if (atEnd && requested != null && session.tryArriveInternal(tick)) return false;
        if (atEnd && session.finishPartialEndpoint()) return false;
        if (atEnd && requested != null) {
            if (session.canMoveDirectlyTo(requested)) return driveDirect(session, mob, speed);
            session.rejectEndpointInternal();
            return false;
        }
        if (session.status() == NavigationStatus.MOVING) {
            if (remaining + 0.05 < bestRemaining) {
                bestRemaining = remaining;
                lastProgressTick = tick;
            } else if (tick - lastProgressTick > BLOCKED_PROGRESS_TICKS) {
                session.beginReplanInternal(tick);
                return false;
            }
        }

        if (segment == null) return false;
        boolean committedJump = !mob.onGround() && landSegment(segment)
                && segment.to().y > segment.from().y + 0.01;
        if (shouldJumpForStep(mob, profile, position, segment)) {
            mob.getJumpControl().jump();
        }
        Vec3 desired;
        if (flight) {
            desired = segment.to();
            double localDistance = Math.max(FLIGHT_LOOKAHEAD, mob.getDeltaMovement().length() * 2.0);
            Vec3 next = position.lerp(desired, Math.min(1, localDistance / Math.max(EPS, position.distanceTo(desired))));
            CollisionWorld.Tri clear = world.segmentClear(position, next, profile);
            if (clear != CollisionWorld.Tri.CLEAR) {
                session.flightObstruction(clear, tick);
                brake(mob, position);
                return false;
            }
        } else if (committedJump) {
            desired = committedJumpAim(position, segment, mob.getDeltaMovement().length());
        } else if (landSegment(segment)) {
            desired = forwardLandAim(position, segment, Math.max(lookahead(segment),
                    Math.min(12, speed * 2)));
        } else {
            double lookahead = Math.max(lookahead(segment), Math.min(12, speed * 2));
            if (remaining < 2.5) lookahead = Math.max(0.35, remaining * 0.6);
            desired = path.pointAt(Math.min(path.length(), routeCursor + lookahead));
        }
        if (desired == null) return false;
        Vec3 steering = flight || committedJump ? desired
                : steeringTarget(world, geometry, profile, position, desired, segment);
        if (steering == null) {
            brake(mob, position);
            return false;
        }
        boolean cornerBrake = steering.distanceToSqr(desired) > 1.0E-8;
        boolean turnBrake = !flight && !committedJump && sharpTurn(path, segment)
                && position.distanceTo(segment.to()) < .32 + 3 * mob.getDeltaMovement().length();
        double brakingDistance = flight ? position.distanceTo(segment.to()) : remaining;
        double scale = brakingDistance < 3.0 ? clamp(brakingDistance / 3.0, flight ? 0.15 : 0.25, 1.0) : 1.0;
        if (cornerBrake || turnBrake) scale *= 0.5;
        mob.getMoveControl().setWantedPosition(steering.x, steering.y, steering.z,
                Math.max(0.05, speed * scale));
        double centerY = (profile.localMinY() + profile.localMaxY()) * 0.5;
        mob.getLookControl().setLookAt(steering.x, steering.y + centerY, steering.z);
        return true;
    }

    boolean driveDirect(NavigationSession session, Mob mob, double speed) {
        Vec3 requested = session.routeGoalView();
        if (requested == null) return false;
        NavigationProfile profile = session.profileView();
        double centerY = (profile.localMinY() + profile.localMaxY()) * 0.5;
        mob.getMoveControl().setWantedPosition(requested.x, requested.y, requested.z,
                Math.max(0.05, speed * 0.5));
        mob.getLookControl().setLookAt(requested.x, requested.y + centerY, requested.z);
        return true;
    }

    private Vec3 steeringTarget(CollisionWorld world, NavigationGeometry geometry,
                                NavigationProfile profile, Vec3 position, Vec3 desired,
                                NavigationPath.Segment segment) {
        if (steerClear(world, geometry, profile, position, desired, segment)) return desired;
        if (segment != null) {
            Vec3 corner = segment.to();
            if (corner.distanceToSqr(position) > 1.0E-8
                    && steerClear(world, geometry, profile, position, corner, segment)) {
                return corner;
            }
        }
        return null;
    }

    private boolean steerClear(CollisionWorld world, NavigationGeometry geometry,
                               NavigationProfile profile, Vec3 from, Vec3 to,
                               NavigationPath.Segment segment) {
        TravelMode mode = segmentMode(segment, profile);
        if (mode == TravelMode.LAND) {
            NavigationPath.Kind kind = NavigationGeometry.classify(profile, from, to);
            if (kind == null) return false;
            return geometry.validateDirect(profile, from, to, kind)
                    == CollisionWorld.Tri.CLEAR;
        }
        if (mode == TravelMode.WATER) {
            return world.waterSegmentClear(from, to, profile) == CollisionWorld.Tri.CLEAR;
        }
        return geometry.validateDirect(profile, from, to, NavigationPath.Kind.FLY)
                == CollisionWorld.Tri.CLEAR;
    }

    private boolean shouldJumpForStep(Mob mob, NavigationProfile profile, Vec3 position,
                                      NavigationPath.Segment segment) {
        if (!mob.onGround() || !landSegment(segment) || segment.to().y <= position.y + EPS) {
            return false;
        }
        boolean needsJump = segment.kind() == NavigationPath.Kind.JUMP
                || segment.to().y - position.y > mob.maxUpStep() + EPS;
        if (!needsJump) return false;
        double dx = segment.to().x - position.x;
        double dz = segment.to().z - position.z;
        double distance = Math.hypot(dx, dz);
        if (distance <= EPS) return false;
        double reachX = .5 + (dx >= 0 ? profile.localMaxX() : -profile.localMinX());
        double reachZ = .5 + (dz >= 0 ? profile.localMaxZ() : -profile.localMinZ());
        double contact = (Math.abs(dx) * reachX + Math.abs(dz) * reachZ) / distance;
        double takeoffDistance = Math.max(1.06, contact + (mob.horizontalCollision ? .28 : .16));
        return distance <= takeoffDistance;
    }

    private boolean sharpTurn(NavigationPath path, NavigationPath.Segment segment) {
        if (!landSegment(segment) || cursorSegment + 1 >= path.segments().size()) return false;
        NavigationPath.Segment following = path.segments().get(cursorSegment + 1);
        if (!landSegment(following)) return true;
        double incomingX = segment.to().x - segment.from().x;
        double incomingZ = segment.to().z - segment.from().z;
        double outgoingX = following.to().x - following.from().x;
        double outgoingZ = following.to().z - following.from().z;
        double scale = Math.hypot(incomingX, incomingZ) * Math.hypot(outgoingX, outgoingZ);
        return scale > EPS && incomingX * outgoingX + incomingZ * outgoingZ < .65 * scale;
    }

    private CollisionWorld.Tri joinFollowingLandSegment(NavigationSession session, Vec3 position,
                                                        NavigationPath.Segment following) {
        NavigationProfile profile = session.profileView();
        if (following.kind() == NavigationPath.Kind.SWIM) {
            NavigationPath.Kind entry = NavigationGeometry.classify(profile, position, following.to());
            if (entry == null) return CollisionWorld.Tri.BLOCKED;
            CollisionWorld.Tri clear = session.geometryView().validateDirect(profile, position,
                    following.to(), entry);
            return clear == CollisionWorld.Tri.CLEAR
                    ? session.worldView().waterImmersion(following.to(), profile) : clear;
        }
        if (!landSegment(following)) return CollisionWorld.Tri.BLOCKED;
        NavigationPath.Kind kind = NavigationGeometry.classify(profile, position, following.to());
        if (kind == null) return CollisionWorld.Tri.BLOCKED;
        return session.geometryView().validateDirect(profile, position, following.to(), kind);
    }

    private boolean reachedLandWaypoint(Vec3 position, Vec3 waypoint) {
        return horizontalDistanceSqr(position, waypoint) <= LAND_WAYPOINT_RADIUS_SQR
                && Math.abs(position.y - waypoint.y) < LAND_WAYPOINT_HEIGHT;
    }

    private boolean passedLandWaypoint(Vec3 position, NavigationPath.Segment segment) {
        Vec3 origin = landLegOrigin == null ? segment.from() : landLegOrigin;
        double dx = segment.to().x - origin.x;
        double dz = segment.to().z - origin.z;
        return dx * dx + dz * dz > EPS
                && (position.x - segment.to().x) * dx + (position.z - segment.to().z) * dz > 0;
    }

    private boolean offLandLeg(Vec3 position, NavigationPath.Segment segment) {
        Vec3 origin = landLegOrigin == null ? segment.from() : landLegOrigin;
        double dx = segment.to().x - origin.x;
        double dz = segment.to().z - origin.z;
        double lengthSqr = dx * dx + dz * dz;
        if (lengthSqr <= EPS) return false;
        double fraction = clamp(((position.x - origin.x) * dx + (position.z - origin.z) * dz)
                / lengthSqr, 0, 1);
        double x = origin.x + dx * fraction;
        double z = origin.z + dz * fraction;
        double lateralX = position.x - x;
        double lateralZ = position.z - z;
        return lateralX * lateralX + lateralZ * lateralZ > LAND_CORRIDOR_RADIUS * LAND_CORRIDOR_RADIUS;
    }

    private Vec3 forwardLandAim(Vec3 position, NavigationPath.Segment segment, double lookahead) {
        Vec3 origin = landLegOrigin == null ? segment.from() : landLegOrigin;
        double dx = segment.to().x - origin.x;
        double dz = segment.to().z - origin.z;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal <= EPS) return segment.to();
        double along = Math.max(0, ((position.x - origin.x) * dx + (position.z - origin.z) * dz)
                / horizontal);
        double distance = Math.min(horizontal, along + lookahead);
        double fraction = distance / horizontal;
        return new Vec3(origin.x + dx * fraction, origin.y + (segment.to().y - origin.y) * fraction,
                origin.z + dz * fraction);
    }

    private Vec3 committedJumpAim(Vec3 position, NavigationPath.Segment segment, double speed) {
        Vec3 origin = landLegOrigin == null ? segment.from() : landLegOrigin;
        double dx = segment.to().x - origin.x;
        double dz = segment.to().z - origin.z;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal <= EPS) return segment.to();
        double lookahead = Math.max(.6, speed * 2);
        return new Vec3(position.x + dx / horizontal * lookahead, Math.max(position.y, segment.to().y),
                position.z + dz / horizontal * lookahead);
    }

    private static boolean landSegment(NavigationPath.Segment segment) {
        return segment != null && segment.kind() != NavigationPath.Kind.SWIM
                && segment.kind() != NavigationPath.Kind.FLY;
    }

    private NavigationPath.Segment currentSegment(NavigationPath path) {
        if (path.segments().isEmpty()) return null;
        int index = Math.min(Math.max(cursorSegment, 0), path.segments().size() - 1);
        return path.segments().get(index);
    }

    private static TravelMode segmentMode(NavigationPath.Segment segment,
                                          NavigationProfile profile) {
        if (segment == null) return profile.primaryMode();
        if (segment.kind() == NavigationPath.Kind.SWIM) return TravelMode.WATER;
        if (segment.kind() == NavigationPath.Kind.FLY) return TravelMode.AIR;
        return TravelMode.LAND;
    }

    private static double lookahead(NavigationPath.Segment segment) {
        if (segment == null) return 1.2;
        if (segment.kind() == NavigationPath.Kind.SWIM) return 1.5;
        if (segment.kind() == NavigationPath.Kind.FLY) return 2.0;
        return 1.2;
    }

    private static void brake(Mob mob, Vec3 position) {
        mob.getMoveControl().setWantedPosition(position.x, position.y, position.z, 0.0);
    }

    private static NavigationPath.Segment.Projection project(Vec3 position,
                                                             NavigationPath.Segment segment) {
        Vec3 delta = segment.to().subtract(segment.from());
        double lengthSqr = delta.lengthSqr();
        double t = lengthSqr < 1.0E-12 ? 0.0
                : clamp(position.subtract(segment.from()).dot(delta) / lengthSqr, 0.0, 1.0);
        return new NavigationPath.Segment.Projection(
                segment.from().add(delta.scale(t)), segment.length() * t);
    }

    private static double horizontalDistanceSqr(Vec3 first, Vec3 second) {
        double dx = first.x - second.x;
        double dz = first.z - second.z;
        return dx * dx + dz * dz;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : value > max ? max : value;
    }
}
