package com.kltyton.companioncall.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable, validated route. Points are real foot positions produced by the planner; segments
 * carry the native movement primitive that formed them.
 */
public final class NavigationPath {
    public enum Kind {
        START,
        WALK,
        STEP_UP,
        STEP_DOWN,
        JUMP,
        DROP,
        SWIM,
        FLY
    }

    public static final class Point {
        private final Vec3 feet;
        private final Kind incoming;

        public Point(Vec3 feet, Kind incoming) {
            if (feet == null) throw new IllegalArgumentException("Point position is required");
            if (incoming == null) throw new IllegalArgumentException("Point kind is required");
            this.feet = feet;
            this.incoming = incoming;
        }

        public Vec3 feet() { return feet; }
        public Kind incoming() { return incoming; }
    }

    public static final class Segment {
        private final Vec3 from;
        private final Vec3 to;
        private final Kind kind;

        public Segment(Vec3 from, Vec3 to, Kind kind) {
            this.from = from;
            this.to = to;
            this.kind = kind;
        }

        public static final class Projection {
            private final Vec3 point;
            private final double distance;

            public Projection(Vec3 point, double distance) {
                this.point = point;
                this.distance = distance;
            }

            public Vec3 point() { return point; }
            public double distance() { return distance; }
        }

        public Vec3 from() { return from; }
        public Vec3 to() { return to; }
        public Kind kind() { return kind; }
        public double length() { return from.distanceTo(to); }
    }

    private static final NavigationPath EMPTY = new NavigationPath(Collections.emptyList());

    private final List<Point> points;
    private final List<Segment> segments;
    private final double[] cumulative;

    private NavigationPath(List<Point> points) {
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        List<Segment> built = new ArrayList<>();
        double[] distances = new double[points.size()];
        double distance = 0.0;
        for (int i = 1; i < points.size(); i++) {
            Point previous = points.get(i - 1);
            Point current = points.get(i);
            distance += previous.feet.distanceTo(current.feet);
            distances[i] = distance;
            built.add(new Segment(previous.feet, current.feet, current.incoming));
        }
        this.segments = Collections.unmodifiableList(built);
        this.cumulative = distances;
    }

    public static NavigationPath empty() {
        return EMPTY;
    }

    public static NavigationPath fromPoints(List<Point> points) {
        if (points == null || points.isEmpty()) return EMPTY;
        return new NavigationPath(points);
    }

    public boolean isEmpty() { return points.isEmpty(); }
    public int size() { return points.size(); }
    public List<Point> points() { return points; }
    public List<Segment> segments() { return segments; }

    public List<Vec3> waypoints() {
        List<Vec3> result = new ArrayList<>(points.size());
        for (Point point : points) result.add(point.feet);
        return Collections.unmodifiableList(result);
    }

    public Vec3 start() { return points.isEmpty() ? null : points.get(0).feet; }
    public Vec3 end() { return points.isEmpty() ? null : points.get(points.size() - 1).feet; }
    public double length() { return cumulative.length == 0 ? 0.0 : cumulative[cumulative.length - 1]; }
    public double distanceAt(int index) {
        if (index <= 0) return 0.0;
        if (index >= cumulative.length) return length();
        return cumulative[index];
    }

    public double closestDistance(Vec3 position) {
        if (points.isEmpty()) return 0.0;
        if (points.size() == 1) return 0.0;
        double bestDistanceSqr = Double.POSITIVE_INFINITY;
        double bestRouteDistance = 0.0;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            Vec3 delta = segment.to.subtract(segment.from);
            double lengthSqr = delta.lengthSqr();
            double t = lengthSqr < 1.0E-12 ? 0.0
                    : clamp(position.subtract(segment.from).dot(delta) / lengthSqr, 0.0, 1.0);
            Vec3 closest = segment.from.add(delta.scale(t));
            double distanceSqr = position.distanceToSqr(closest);
            if (distanceSqr < bestDistanceSqr) {
                bestDistanceSqr = distanceSqr;
                bestRouteDistance = cumulative[i] + segment.length() * t;
            }
        }
        return bestRouteDistance;
    }

    public double remainingDistance(Vec3 position) {
        if (points.isEmpty()) return 0.0;
        return Math.max(0.0, length() - closestDistance(position));
    }

    public Vec3 pointAt(double distance) {
        if (points.isEmpty()) return null;
        if (points.size() == 1) return points.get(0).feet;
        double clamped = clamp(distance, 0.0, length());
        for (int i = 0; i < segments.size(); i++) {
            double start = cumulative[i];
            double end = cumulative[i + 1];
            if (clamped <= end || i == segments.size() - 1) {
                Segment segment = segments.get(i);
                double span = end - start;
                double t = span < 1.0E-12 ? 0.0 : (clamped - start) / span;
                return segment.from.add(segment.to.subtract(segment.from).scale(t));
            }
        }
        return end();
    }

    public Segment segmentAt(double distance) {
        if (segments.isEmpty()) return null;
        double clamped = clamp(distance, 0.0, length());
        for (int i = 0; i < segments.size(); i++) {
            if (clamped <= cumulative[i + 1] || i == segments.size() - 1) {
                return segments.get(i);
            }
        }
        return segments.get(segments.size() - 1);
    }

    /** Appends a route that begins at this route's end, dropping the duplicated first point. */
    public NavigationPath append(NavigationPath tail) {
        if (tail == null || tail.isEmpty()) return this;
        if (this.isEmpty()) return tail;
        List<Point> combined = new ArrayList<>(points);
        Vec3 last = end();
        for (int i = 0; i < tail.points.size(); i++) {
            Point point = tail.points.get(i);
            if (i == 0 && last.distanceToSqr(point.feet) < 1.0E-10) continue;
            combined.add(point);
        }
        return fromPoints(combined);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : value > max ? max : value;
    }
}
