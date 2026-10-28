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
    private static final double EPS = 1.0E-5;

    private int cursorSegment;
    private double routeCursor;
    private double bestRemaining = Double.POSITIVE_INFINITY;
    private long lastProgressTick;

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
        int limit = Math.min(path.segments().size() - 1, 8);
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
        for (int advance = 0; advance <= 2; advance++) {
            NavigationPath.Segment segment = segments.get(cursorSegment);
            NavigationPath.Segment.Projection projection = project(position, segment);
            double candidate = path.distanceAt(cursorSegment) + projection.distance();
            if (candidate > routeCursor) routeCursor = candidate;
            if (cursorSegment >= segments.size() - 1) break;
            double end = path.distanceAt(cursorSegment + 1);
            if (routeCursor < end - 0.2) break;
            if (position.distanceToSqr(segment.to()) > 1.0) break;
            cursorSegment++;
        }
    }

    double routeCursor() { return routeCursor; }
    int cursorSegment() { return cursorSegment; }

    void progressChanged(NavigationSession session, long tick) {
        bestRemaining = Math.max(0.0, session.pathView().length() - routeCursor);
        lastProgressTick = tick;
    }

    Vec3 waypoint(NavigationSession session) {
        NavigationPath path = session.pathView();
        if (path.isEmpty()) return null;
        updateCursor(session.entity().position(), path);
        NavigationPath.Segment segment = currentSegment(path);
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
        Vec3 desired = path.pointAt(Math.min(path.length(), routeCursor + Math.max(2, speed * 2)));
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
        updateCursor(position, path);

        Vec3 requested = session.requestedGoalView();
        if (requested != null) {
            double arrival = NavigationGeometry.arrivalRadius(session.targetModeView());
            if (position.distanceToSqr(requested) <= arrival * arrival
                    && session.tryArriveInternal(tick)) {
                return false;
            }
        }
        double remaining = Math.max(0.0, path.length() - routeCursor);
        boolean atEnd = remaining <= 0.15 || position.distanceToSqr(path.end()) <= 0.25;
        if (atEnd && requested != null && !session.tryArriveInternal(tick)) {
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

        NavigationPath.Segment segment = currentSegment(path);
        if (segment == null) return false;
        if (segment.to().y > segment.from().y + 0.01
                && (segment.kind() == NavigationPath.Kind.JUMP
                || segment.kind() == NavigationPath.Kind.STEP_UP)
                && mob.onGround()
                && horizontalDistanceSqr(position, segment.to()) <= 1.35 * 1.35) {
            mob.getJumpControl().jump();
        }
        double lookahead = Math.max(lookahead(segment), Math.min(12, speed * 2));
        if (remaining < 2.5) lookahead = Math.max(0.35, remaining * 0.6);
        Vec3 desired = path.pointAt(Math.min(path.length(), routeCursor + lookahead));
        if (desired == null) return false;
        Vec3 steering = steeringTarget(world, geometry, profile, position, desired, segment);
        if (steering == null) {
            brake(mob, position);
            return false;
        }
        boolean cornerBrake = steering.distanceToSqr(desired) > 1.0E-8;
        double scale = remaining < 3.0 ? clamp(remaining / 3.0, 0.25, 1.0) : 1.0;
        if (cornerBrake) scale *= 0.5;
        mob.getMoveControl().setWantedPosition(steering.x, steering.y, steering.z,
                Math.max(0.05, speed * scale));
        double centerY = (profile.localMinY() + profile.localMaxY()) * 0.5;
        mob.getLookControl().setLookAt(steering.x, steering.y + centerY, steering.z);
        return true;
    }

    boolean driveDirect(NavigationSession session, Mob mob, double speed) {
        Vec3 requested = session.requestedGoalView();
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
