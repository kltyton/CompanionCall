package com.kltyton.companioncall.navigation;

import com.kltyton.companioncall.api.TravelMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.world.phys.Vec3;

/**
 * Incremental A* over genuine land, water and air movement primitives. The open queue survives
 * between server ticks, so a request can be planned with a small per-tick expansion budget.
 */
final class AStarPlanner {
    static final int NODE_LIMIT = 8192;

    enum Progress { EXPANDED, FOUND, LIMITED, WAITING_REGION, NO_ROUTE }

    static final class Result {
        final Progress progress;
        final NavigationPath path;
        final int used;

        Result(Progress progress, NavigationPath path, int used) {
            this.progress = progress;
            this.path = path;
            this.used = used;
        }
    }

    private static final double EPS = 1.0E-7;
    private static final int[][] HORIZONTAL = {
            { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
            { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 }
    };

    private static final class Key {
        final long x;
        final long y;
        final long z;
        final TravelMode mode;

        Key(Vec3 feet, TravelMode mode) {
            this.x = Math.round(feet.x * 4096.0);
            this.y = Math.round(feet.y * 4096.0);
            this.z = Math.round(feet.z * 4096.0);
            this.mode = mode;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Key key)) return false;
            return x == key.x && y == key.y && z == key.z && mode == key.mode;
        }

        @Override public int hashCode() {
            int result = Long.hashCode(x);
            result = 31 * result + Long.hashCode(y);
            result = 31 * result + Long.hashCode(z);
            result = 31 * result + mode.hashCode();
            return result;
        }
    }

    private static final class Node {
        final Vec3 feet;
        final Node parent;
        final NavigationPath.Kind kind;
        final TravelMode mode;
        final double g;
        final double h;
        final double f;
        final long sequence;

        Node(Vec3 feet, Node parent, NavigationPath.Kind kind, TravelMode mode,
             double g, double h, long sequence) {
            this.feet = feet;
            this.parent = parent;
            this.kind = kind;
            this.mode = mode;
            this.g = g;
            this.h = h;
            this.f = g + h;
            this.sequence = sequence;
        }
    }

    private final CollisionWorld world;
    private final NavigationProfile profile;
    private final Vec3 goal;
    private final TravelMode goalMode;
    private final int nodeLimit;
    private final PriorityQueue<Node> open = new PriorityQueue<>(
            Comparator.comparingDouble((Node node) -> node.f)
                    .thenComparingDouble(node -> node.h)
                    .thenComparingLong(node -> node.sequence));
    private final Map<Key, Double> bestG = new HashMap<>();
    private final Set<Key> closed = new HashSet<>();
    private boolean unknownSeen;
    private boolean limitHit;
    private long sequence;
    private Node closest;

    AStarPlanner(CollisionWorld world, NavigationProfile profile, Vec3 start,
                 TravelMode startMode, Vec3 goal, TravelMode goalMode) {
        this(world, profile, start, startMode, goal, goalMode, NODE_LIMIT);
    }

    AStarPlanner(CollisionWorld world, NavigationProfile profile, Vec3 start,
                 TravelMode startMode, Vec3 goal, TravelMode goalMode, int nodeLimit) {
        this.world = world;
        this.profile = profile;
        this.goal = goal;
        this.goalMode = goalMode;
        this.nodeLimit = Math.max(1, nodeLimit);
        Node startNode = new Node(start, null, NavigationPath.Kind.START, startMode, 0.0,
                heuristic(start), sequence++);
        closest = startNode;
        bestG.put(new Key(start, startMode), 0.0);
        open.add(startNode);
    }

    Result step(int maxExpansions) {
        world.beginStep();
        if (maxExpansions <= 0) {
            return new Result(Progress.EXPANDED, null, 0);
        }
        int used = 0;
        while (used < maxExpansions) {
            Node current = open.poll();
            if (current == null) {
                return new Result(unknownSeen ? Progress.WAITING_REGION : Progress.NO_ROUTE,
                        null, used);
            }
            Key key = new Key(current.feet, current.mode);
            Double best = bestG.get(key);
            if (best != null && current.g > best + EPS) continue;
            if (!closed.add(key)) continue;
            used++;
            if (current.h < closest.h) closest = current;
            if (atGoal(current)) {
                return new Result(Progress.FOUND, buildPath(current), used);
            }
            expand(current);
            if (limitHit) {
                return new Result(Progress.LIMITED, null, used);
            }
        }
        return new Result(Progress.EXPANDED, null, used);
    }

    NavigationPath closestPath() {
        return closest.parent == null ? NavigationPath.empty() : buildPath(closest);
    }

    private void expand(Node current) {
        if (current.mode == TravelMode.LAND) {
            expandLand(current);
        } else if (current.mode == TravelMode.WATER) {
            expandWater(current);
        } else {
            expandAir(current);
        }
    }

    private void expandLand(Node current) {
        int blockX = floor(current.feet.x);
        int blockZ = floor(current.feet.z);
        for (int[] direction : HORIZONTAL) {
            CollisionWorld.CenterResult centers = world.horizontalCandidates(
                    blockX + direction[0], blockZ + direction[1], current.feet.y, profile);
            if (centers.status == CollisionWorld.Tri.UNKNOWN) {
                unknownSeen = true;
                continue;
            }
            if (centers.status != CollisionWorld.Tri.CLEAR) continue;
            for (int centerIndex = 0; centerIndex < centers.centers.size(); centerIndex++) {
                Vec3 center = centers.centers.get(centerIndex);
                CollisionWorld.StandResult stands = world.standHeights(
                        center.x, center.z, current.feet.y, profile);
                if (stands.status == CollisionWorld.Tri.UNKNOWN) {
                    unknownSeen = true;
                    continue;
                }
                if (stands.status != CollisionWorld.Tri.CLEAR) continue;
                boolean added = false;
                for (double height : stands.heights) {
                    Vec3 target = new Vec3(center.x, height, center.z);
                    if (target.distanceToSqr(current.feet) < 1.0E-8) continue;
                    double dy = target.y - current.feet.y;
                    NavigationPath.Kind kind = kindFor(dy);
                    if (kind == null) continue;
                    CollisionWorld.Tri clear = edgeClear(current.feet, target, kind);
                    if (clear == CollisionWorld.Tri.UNKNOWN) {
                        unknownSeen = true;
                        continue;
                    }
                    if (clear != CollisionWorld.Tri.CLEAR) continue;
                    if (add(current, target, kind, TravelMode.LAND,
                            cost(current.feet, target, kind))) {
                        added = true;
                    }
                }
                // The centre candidate is preferred; gap midpoints are the genuine narrow-passage
                // fallback only when the voxel centre produced no supported, clear step.
                if (centerIndex == 0 && added) break;
            }
        }
        if (profile.allows(TravelMode.WATER)) expandLandToWater(current);
        if (profile.allows(TravelMode.AIR)) {
            CollisionWorld.Tri body = world.bodyClear(current.feet, profile);
            if (body == CollisionWorld.Tri.UNKNOWN) {
                unknownSeen = true;
            } else if (body == CollisionWorld.Tri.CLEAR) {
                add(current, current.feet, NavigationPath.Kind.FLY, TravelMode.AIR, 0.5);
            }
        }
    }

    private void expandLandToWater(Node current) {
        int blockX = floor(current.feet.x);
        int blockZ = floor(current.feet.z);
        for (int[] direction : HORIZONTAL) {
            CollisionWorld.CenterResult water = world.waterCandidates(
                    blockX + direction[0], blockZ + direction[1], current.feet.y, profile);
            if (water.status == CollisionWorld.Tri.UNKNOWN) {
                unknownSeen = true;
                continue;
            }
            if (water.status != CollisionWorld.Tri.CLEAR) continue;
            for (Vec3 target : water.centers) {
                CollisionWorld.Tri clear = entryClear(current.feet, target);
                if (clear == CollisionWorld.Tri.UNKNOWN) {
                    unknownSeen = true;
                    continue;
                }
                if (clear != CollisionWorld.Tri.CLEAR) continue;
                add(current, target, NavigationPath.Kind.SWIM, TravelMode.WATER,
                        current.feet.distanceTo(target) + 0.5);
            }
        }
    }

    private void expandWater(Node current) {
        expandVolume(current, TravelMode.WATER);
        if (profile.allows(TravelMode.LAND)) expandToLand(current);
    }

    private void expandAir(Node current) {
        expandVolume(current, TravelMode.AIR);
        if (profile.allows(TravelMode.LAND)) expandToLand(current);
    }

    private void expandVolume(Node current, TravelMode mode) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    Vec3 target = current.feet.add(dx, dy, dz);
                    CollisionWorld.Tri clear = world.segmentClear(current.feet, target, profile);
                    if (clear == CollisionWorld.Tri.UNKNOWN) {
                        unknownSeen = true;
                        continue;
                    }
                    if (clear != CollisionWorld.Tri.CLEAR) continue;
                    if (mode == TravelMode.WATER) {
                        CollisionWorld.Tri immersion = world.waterImmersion(target, profile);
                        if (immersion == CollisionWorld.Tri.UNKNOWN) {
                            unknownSeen = true;
                            continue;
                        }
                        if (immersion != CollisionWorld.Tri.CLEAR) continue;
                    }
                    NavigationPath.Kind kind = mode == TravelMode.WATER
                            ? NavigationPath.Kind.SWIM : NavigationPath.Kind.FLY;
                    add(current, target, kind, mode, current.feet.distanceTo(target));
                }
            }
        }
    }

    private void expandToLand(Node current) {
        int blockX = floor(current.feet.x);
        int blockZ = floor(current.feet.z);
        for (int[] direction : HORIZONTAL) {
            CollisionWorld.CenterResult centers = world.horizontalCandidates(
                    blockX + direction[0], blockZ + direction[1], current.feet.y, profile);
            if (centers.status == CollisionWorld.Tri.UNKNOWN) {
                unknownSeen = true;
                continue;
            }
            if (centers.status != CollisionWorld.Tri.CLEAR) continue;
            for (int centerIndex = 0; centerIndex < centers.centers.size(); centerIndex++) {
                Vec3 center = centers.centers.get(centerIndex);
                CollisionWorld.StandResult stands = world.standHeights(
                        center.x, center.z, current.feet.y, profile);
                if (stands.status == CollisionWorld.Tri.UNKNOWN) {
                    unknownSeen = true;
                    continue;
                }
                if (stands.status != CollisionWorld.Tri.CLEAR) continue;
                boolean added = false;
                for (double height : stands.heights) {
                    Vec3 target = new Vec3(center.x, height, center.z);
                    if (target.distanceToSqr(current.feet) < 1.0E-8) continue;
                    double dy = target.y - current.feet.y;
                    NavigationPath.Kind kind = kindFor(dy);
                    if (kind == null) continue;
                    CollisionWorld.Tri clear = transitionLandClear(current.feet, target, kind);
                    if (clear == CollisionWorld.Tri.UNKNOWN) {
                        unknownSeen = true;
                        continue;
                    }
                    if (clear != CollisionWorld.Tri.CLEAR) continue;
                    if (add(current, target, kind, TravelMode.LAND,
                            current.feet.distanceTo(target) + 0.5)) {
                        added = true;
                    }
                }
                if (centerIndex == 0 && added) break;
            }
        }
    }

    private CollisionWorld.Tri entryClear(Vec3 from, Vec3 to) {
        double dy = to.y - from.y;
        CollisionWorld.Tri clear;
        if (dy < -1.0E-4) {
            if (!profile.canDrop() || -dy > profile.maxDropHeight() + 1.0E-4) {
                return CollisionWorld.Tri.BLOCKED;
            }
            clear = world.dropClear(from, to, profile);
        } else if (dy > 1.0E-4) {
            clear = world.stepClear(from, to, profile);
        } else {
            clear = world.segmentClear(from, to, profile);
        }
        if (clear != CollisionWorld.Tri.CLEAR) return clear;
        return world.waterImmersion(to, profile);
    }

    private CollisionWorld.Tri transitionLandClear(Vec3 from, Vec3 to, NavigationPath.Kind kind) {
        if (kind == NavigationPath.Kind.WALK) return world.segmentClear(from, to, profile);
        if (kind == NavigationPath.Kind.STEP_UP) return world.stepClear(from, to, profile);
        if (kind == NavigationPath.Kind.JUMP) return world.jumpClear(from, to, profile);
        return world.dropClear(from, to, profile);
    }

    private CollisionWorld.Tri edgeClear(Vec3 from, Vec3 to, NavigationPath.Kind kind) {
        if (kind == NavigationPath.Kind.WALK) {
            CollisionWorld.Tri clear = world.segmentClear(from, to, profile);
            if (clear != CollisionWorld.Tri.CLEAR) return clear;
            return world.groundedSegmentResult(from, to, profile);
        }
        if (kind == NavigationPath.Kind.STEP_UP) return world.stepClear(from, to, profile);
        if (kind == NavigationPath.Kind.JUMP) return world.jumpClear(from, to, profile);
        if (kind == NavigationPath.Kind.DROP) return world.dropClear(from, to, profile);
        return world.segmentClear(from, to, profile);
    }

    private NavigationPath.Kind kindFor(double dy) {
        if (Math.abs(dy) <= 1.0E-4) return NavigationPath.Kind.WALK;
        if (dy > 0.0) {
            if (dy <= profile.maxUpStep() + 1.0E-4) return NavigationPath.Kind.STEP_UP;
            if (profile.canJump() && dy <= profile.maxJumpHeight() + 1.0E-4) {
                return NavigationPath.Kind.JUMP;
            }
            return null;
        }
        if (!profile.canDrop() || -dy > profile.maxDropHeight() + 1.0E-4) return null;
        return NavigationPath.Kind.DROP;
    }

    private boolean add(Node parent, Vec3 feet, NavigationPath.Kind kind, TravelMode mode,
                        double cost) {
        Key key = new Key(feet, mode);
        double score = parent.g + cost;
        Double existing = bestG.get(key);
        if (existing != null && existing <= score + EPS) return false;
        if (existing == null && bestG.size() >= nodeLimit) {
            limitHit = true;
            return false;
        }
        bestG.put(key, score);
        closed.remove(key);
        open.add(new Node(feet, parent, kind, mode, score, heuristic(feet), sequence++));
        return true;
    }

    private double heuristic(Vec3 feet) {
        return feet.distanceTo(goal);
    }

    private double cost(Vec3 from, Vec3 to, NavigationPath.Kind kind) {
        double cost = from.distanceTo(to);
        if (kind == NavigationPath.Kind.JUMP) cost += 0.5;
        else if (kind == NavigationPath.Kind.DROP) cost += 0.1;
        return cost;
    }

    private boolean atGoal(Node node) {
        if (node.mode != goalMode) return false;
        double dx = node.feet.x - goal.x;
        double dz = node.feet.z - goal.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double verticalTolerance = goalMode == TravelMode.LAND ? 0.4 : 0.7;
        if (horizontal > 0.75 || Math.abs(node.feet.y - goal.y) > verticalTolerance) {
            return false;
        }
        return goalClear(node);
    }

    private boolean goalClear(Node node) {
        if (node.mode == TravelMode.LAND) {
            NavigationPath.Kind kind = kindFor(goal.y - node.feet.y);
            if (kind == null) return false;
            if (kind == NavigationPath.Kind.WALK) {
                CollisionWorld.Tri clear = world.segmentClear(node.feet, goal, profile);
                if (clear != CollisionWorld.Tri.CLEAR) return false;
                return world.groundedSegmentResult(node.feet, goal, profile)
                        == CollisionWorld.Tri.CLEAR;
            }
            return edgeClear(node.feet, goal, kind) == CollisionWorld.Tri.CLEAR;
        }
        CollisionWorld.Tri clear = world.segmentClear(node.feet, goal, profile);
        if (clear != CollisionWorld.Tri.CLEAR) return false;
        if (node.mode == TravelMode.WATER) {
            return world.waterImmersion(goal, profile) == CollisionWorld.Tri.CLEAR;
        }
        return world.bodyClear(goal, profile) == CollisionWorld.Tri.CLEAR;
    }

    private NavigationPath buildPath(Node end) {
        Deque<Node> chain = new ArrayDeque<>();
        for (Node node = end; node != null; node = node.parent) chain.addFirst(node);
        List<NavigationPath.Point> points = new ArrayList<>(chain.size());
        for (Node node : chain) {
            points.add(new NavigationPath.Point(node.feet, node.kind));
        }
        return NavigationPath.fromPoints(points);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
