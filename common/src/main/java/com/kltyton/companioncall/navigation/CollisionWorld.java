package com.kltyton.companioncall.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Read-only server-thread view of native collision and fluid state.
 *
 * <p>No chunk is ever requested here. A block in an unloaded chunk produces {@link Tri#UNKNOWN};
 * callers must treat that as an explicit region wait rather than air. The cell cache belongs to one
 * session/entity and all shapes are evaluated with that entity's real {@link CollisionContext}.</p>
 */
final class CollisionWorld {
    enum Tri { CLEAR, BLOCKED, UNKNOWN }

    static final class BoxSet {
        final Tri status;
        final List<AABB> boxes;

        BoxSet(Tri status, List<AABB> boxes) {
            this.status = status;
            this.boxes = boxes;
        }
    }

    static final class StandResult {
        final Tri status;
        final List<Double> heights;

        StandResult(Tri status, List<Double> heights) {
            this.status = status;
            this.heights = heights;
        }
    }

    static final class CenterResult {
        final Tri status;
        final List<Vec3> centers;

        CenterResult(Tri status, List<Vec3> centers) {
            this.status = status;
            this.centers = centers;
        }
    }

    static final class GroundResult {
        final Tri status;
        final Vec3 point;

        GroundResult(Tri status, Vec3 point) {
            this.status = status;
            this.point = point;
        }
    }

    static final class FluidResult {
        final Tri status;
        final boolean water;
        final boolean lava;
        final double top;

        FluidResult(Tri status, boolean water, boolean lava, double top) {
            this.status = status;
            this.water = water;
            this.lava = lava;
            this.top = top;
        }
    }

    private static final class Cell {
        final List<AABB> boxes;
        final FluidState fluid;

        Cell(List<AABB> boxes, FluidState fluid) {
            this.boxes = boxes;
            this.fluid = fluid;
        }
    }

    private record GroundProbe(double x, double y, double z, double minX, double minY,
                               double minZ, double maxX, double maxY, double maxZ) {}

    private static final double EPS = 1.0E-7;
    private static final double TOUCH = 1.0E-4;
    private static final double MAX_SWEEP_SPAN = 6.0;
    private static final double TREE_GROUND_SEARCH_DEPTH = 24.0;

    private final ServerLevel level;
    private final CollisionContext context;
    private final int minY;
    private final int maxYExclusive;
    private final Map<BlockPos, Cell> cells = new HashMap<>();
    private final Map<GroundProbe, GroundResult> groundDestinations = new HashMap<>();
    private Entity approachObstacle;

    CollisionWorld(ServerLevel level, Entity entity) {
        if (entity == null) throw new IllegalArgumentException("Collision entity is required");
        this.level = level;
        this.context = CollisionContext.of(entity);
        this.minY = com.kltyton.companioncall.platform.NativeVersion.minY(level);
        this.maxYExclusive = this.minY + level.getHeight();
    }

    void beginStep() {
        cells.clear();
        groundDestinations.clear();
    }

    void approachObstacle(Entity obstacle) {
        approachObstacle = obstacle;
    }

    boolean insideBuildHeight(Vec3 feet, NavigationProfile profile) {
        AABB body = profile.boxAt(feet);
        return body.minY >= minY && body.maxY <= maxYExclusive;
    }

    boolean insideBorder(AABB box) {
        return level.getWorldBorder().isWithinBounds(box);
    }

    BoxSet collisionBoxes(AABB query) {
        requireServerThread();
        if (query.maxY < minY || query.minY >= maxYExclusive) {
            return new BoxSet(Tri.CLEAR, Collections.emptyList());
        }
        int minBX = floor(query.minX);
        int maxBX = floor(query.maxX);
        int minBZ = floor(query.minZ);
        int maxBZ = floor(query.maxZ);
        int minBY = Math.max(minY, floor(query.minY) - 1);
        int maxBY = Math.min(maxYExclusive - 1, floor(query.maxY) + 1);
        if (minBY > maxBY) return new BoxSet(Tri.CLEAR, Collections.emptyList());
        // BlockCollisions inspects one block outside the query, and a border block's collision
        // shape may inspect one further neighbour. Verify the wider region before any read so no
        // getBlockState call can generate or load a chunk.
        if (!chunksLoaded(minBX - 2, maxBX + 2, minBZ - 2, maxBZ + 2)) {
            return new BoxSet(Tri.UNKNOWN, Collections.emptyList());
        }
        List<AABB> result = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(minBX - 1, minBY, minBZ - 1,
                maxBX + 1, maxBY, maxBZ + 1)) {
            BlockPos immutable = pos.immutable();
            Cell cell = cell(immutable);
            for (AABB box : cell.boxes) {
                if (box.intersects(query)) result.add(box);
            }
        }
        return new BoxSet(Tri.CLEAR, result);
    }

    Tri bodyClear(Vec3 feet, NavigationProfile profile) {
        if (!insideBuildHeight(feet, profile)) return Tri.BLOCKED;
        AABB body = profile.boxAt(feet);
        if (!insideBorder(body)) return Tri.BLOCKED;
        if (approachObstacle != null && body.deflate(.001).intersects(approachObstacle.getBoundingBox().deflate(.001))) return Tri.BLOCKED;
        BoxSet set = collisionBoxes(body.deflate(EPS));
        if (set.status != Tri.CLEAR) return set.status;
        for (AABB box : set.boxes) {
            if (box.intersects(body.deflate(TOUCH))) return Tri.BLOCKED;
        }
        return Tri.CLEAR;
    }

    Tri segmentClear(Vec3 from, Vec3 to, NavigationProfile profile) {
        if (!insideBuildHeight(from, profile) || !insideBuildHeight(to, profile)) return Tri.BLOCKED;
        AABB fromBox = profile.boxAt(from);
        AABB toBox = profile.boxAt(to);
        if (!insideBorder(fromBox) || !insideBorder(toBox)) return Tri.BLOCKED;
        if (approachObstacle != null) {
            AABB other = approachObstacle.getBoundingBox();
            AABB expanded = new AABB(other.minX - profile.localMaxX(), other.minY - profile.localMaxY(),
                    other.minZ - profile.localMaxZ(), other.maxX - profile.localMinX(),
                    other.maxY - profile.localMinY(), other.maxZ - profile.localMinZ()).deflate(.001);
            if (segmentIntersects(expanded, from, to)
                    && !exitsOutward(expanded, from, to)) return Tri.BLOCKED;
        }
        BoxSet set = sweptCollisionBoxes(from, to, profile);
        if (set.status != Tri.CLEAR) return set.status;
        for (AABB box : set.boxes) {
            AABB expanded = new AABB(box.minX - profile.localMaxX(),
                    box.minY - profile.localMaxY(), box.minZ - profile.localMaxZ(),
                    box.maxX - profile.localMinX(), box.maxY - profile.localMinY(),
                    box.maxZ - profile.localMinZ()).deflate(EPS);
            if (segmentIntersects(expanded, from, to)) return Tri.BLOCKED;
        }
        return Tri.CLEAR;
    }

    private static boolean inside(AABB box, Vec3 point) {
        return point.x > box.minX && point.x < box.maxX && point.y > box.minY && point.y < box.maxY
                && point.z > box.minZ && point.z < box.maxZ;
    }

    private static boolean exitsOutward(AABB box, Vec3 from, Vec3 to) {
        if (!inside(box, from) || inside(box, to)) return false;
        return to.x <= box.minX && from.x <= (box.minX + box.maxX) * .5
                || to.x >= box.maxX && from.x >= (box.minX + box.maxX) * .5
                || to.y <= box.minY && from.y <= (box.minY + box.maxY) * .5
                || to.y >= box.maxY && from.y >= (box.minY + box.maxY) * .5
                || to.z <= box.minZ && from.z <= (box.minZ + box.maxZ) * .5
                || to.z >= box.maxZ && from.z >= (box.minZ + box.maxZ) * .5;
    }

    private BoxSet sweptCollisionBoxes(Vec3 from, Vec3 to, NavigationProfile profile) {
        int spans = Math.max(1, (int) Math.ceil(from.distanceTo(to) / MAX_SWEEP_SPAN));
        List<AABB> result = new ArrayList<>();
        Vec3 start = from;
        for (int index = 1; index <= spans; index++) {
            Vec3 end = index == spans ? to : from.lerp(to, index / (double) spans);
            AABB sweep = union(profile.boxAt(start), profile.boxAt(end)).inflate(0.01);
            BoxSet boxes = collisionBoxes(sweep);
            if (boxes.status != Tri.CLEAR) return boxes;
            result.addAll(boxes.boxes);
            start = end;
        }
        return new BoxSet(Tri.CLEAR, result);
    }

    Tri stepClear(Vec3 from, Vec3 to, NavigationProfile profile) {
        double dy = to.y - from.y;
        if (dy <= EPS) return dropClear(from, to, profile);
        Vec3 lifted = new Vec3(from.x, to.y, from.z);
        Tri rise = segmentClear(from, lifted, profile);
        if (rise != Tri.CLEAR) return rise;
        return segmentClear(lifted, to, profile);
    }

    Tri jumpClear(Vec3 from, Vec3 to, NavigationProfile profile) {
        if (to.y <= from.y + EPS) return segmentClear(from, to, profile);
        Vec3 lifted = new Vec3(from.x, to.y, from.z);
        Tri rise = segmentClear(from, lifted, profile);
        if (rise != Tri.CLEAR) return rise;
        return segmentClear(lifted, to, profile);
    }

    Tri dropClear(Vec3 from, Vec3 to, NavigationProfile profile) {
        if (to.y >= from.y - EPS) return segmentClear(from, to, profile);
        Vec3 level = new Vec3(to.x, from.y, to.z);
        Tri forward = segmentClear(from, level, profile);
        if (forward != Tri.CLEAR) return forward;
        return segmentClear(level, to, profile);
    }

    StandResult standHeights(double x, double z, double nearY, NavigationProfile profile) {
        requireServerThread();
        double maxRise = Math.max(profile.maxUpStep(),
                profile.canJump() ? profile.maxJumpHeight() : 0.0);
        double minFeet = nearY - (profile.canDrop() ? profile.maxDropHeight() : 0.0);
        double maxFeet = nearY + maxRise;
        double minTop = minFeet + profile.localMinY() - 0.05;
        double maxTop = maxFeet + profile.localMinY() + 0.05;
        AABB query = new AABB(x + profile.localMinX(), minTop, z + profile.localMinZ(),
                x + profile.localMaxX(), maxTop, z + profile.localMaxZ());
        BoxSet set = collisionBoxes(query);
        if (set.status != Tri.CLEAR) return new StandResult(set.status, Collections.emptyList());
        List<Double> candidates = new ArrayList<>();
        for (AABB box : set.boxes) {
            if (!overlapsFootprint(box, x, z, profile)) continue;
            double feet = box.maxY - profile.localMinY();
            if (feet < minFeet - TOUCH || feet > maxFeet + TOUCH) continue;
            boolean duplicate = false;
            for (double existing : candidates) {
                if (Math.abs(existing - feet) <= TOUCH) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) candidates.add(feet);
        }
        candidates.sort(Comparator.comparingDouble(value -> Math.abs(value - nearY)));
        List<Double> valid = new ArrayList<>();
        for (double feet : candidates) {
            if (valid.size() >= 4) break;
            Tri clear = bodyClear(new Vec3(x, feet, z), profile);
            if (clear == Tri.UNKNOWN) return new StandResult(Tri.UNKNOWN, Collections.emptyList());
            if (clear == Tri.CLEAR) valid.add(feet);
        }
        return new StandResult(Tri.CLEAR, valid);
    }

    Tri groundedSegmentResult(Vec3 from, Vec3 to, NavigationProfile profile) {
        requireServerThread();
        if (Math.abs(from.y - to.y) > 0.01) return Tri.BLOCKED;
        double supportY = from.y + profile.localMinY();
        BoxSet set = groundedCollisionBoxes(from, to, profile, supportY);
        if (set.status != Tri.CLEAR) return set.status;
        List<double[]> intervals = new ArrayList<>();
        for (AABB box : set.boxes) {
            if (Math.abs(box.maxY - supportY) > 0.02) continue;
            double[] xInterval = new double[2];
            double[] zInterval = new double[2];
            if (!lineInterval(from.x, to.x, box.minX - profile.localMaxX(),
                    box.maxX - profile.localMinX(), xInterval)) continue;
            if (!lineInterval(from.z, to.z, box.minZ - profile.localMaxZ(),
                    box.maxZ - profile.localMinZ(), zInterval)) continue;
            double t0 = Math.max(0.0, Math.max(xInterval[0], zInterval[0]));
            double t1 = Math.min(1.0, Math.min(xInterval[1], zInterval[1]));
            if (t0 < t1 - EPS) intervals.add(new double[] { t0, t1 });
        }
        if (intervals.isEmpty()) return Tri.BLOCKED;
        intervals.sort(Comparator.comparingDouble(interval -> interval[0]));
        double covered = 0.0;
        for (double[] interval : intervals) {
            if (interval[0] > covered + 0.001) return Tri.BLOCKED;
            if (interval[1] > covered) covered = interval[1];
            if (covered >= 1.0 - EPS) return Tri.CLEAR;
        }
        return covered >= 1.0 - 0.001 ? Tri.CLEAR : Tri.BLOCKED;
    }

    private BoxSet groundedCollisionBoxes(Vec3 from, Vec3 to, NavigationProfile profile,
                                          double supportY) {
        double horizontal = Math.sqrt(from.subtract(to).horizontalDistanceSqr());
        int spans = Math.max(1, (int) Math.ceil(horizontal / MAX_SWEEP_SPAN));
        List<AABB> result = new ArrayList<>();
        Vec3 start = from;
        for (int index = 1; index <= spans; index++) {
            Vec3 end = index == spans ? to : from.lerp(to, index / (double) spans);
            double minX = Math.min(start.x, end.x) + profile.localMinX() - 0.01;
            double maxX = Math.max(start.x, end.x) + profile.localMaxX() + 0.01;
            double minZ = Math.min(start.z, end.z) + profile.localMinZ() - 0.01;
            double maxZ = Math.max(start.z, end.z) + profile.localMaxZ() + 0.01;
            BoxSet boxes = collisionBoxes(new AABB(minX, supportY - 0.03, minZ,
                    maxX, supportY + 0.03, maxZ));
            if (boxes.status != Tri.CLEAR) return boxes;
            result.addAll(boxes.boxes);
            start = end;
        }
        return new BoxSet(Tri.CLEAR, result);
    }

    GroundResult groundDestination(Vec3 selected, NavigationProfile profile) {
        GroundProbe probe = new GroundProbe(selected.x, selected.y, selected.z,
                profile.localMinX(), profile.localMinY(), profile.localMinZ(),
                profile.localMaxX(), profile.localMaxY(), profile.localMaxZ());
        GroundResult cached = groundDestinations.get(probe);
        if (cached != null) return cached;
        GroundResult result = findGroundDestination(selected, profile);
        groundDestinations.put(probe, result);
        return result;
    }

    private GroundResult findGroundDestination(Vec3 selected, NavigationProfile profile) {
        BlockPos canopy = new BlockPos(floor(selected.x), floor(selected.y - EPS), floor(selected.z));
        if (!hasChunk(canopy)) return new GroundResult(Tri.UNKNOWN, null);
        if (!isTreeCanopy(canopy)) return new GroundResult(Tri.CLEAR, null);

        boolean unknown = false;
        int[][] offsets = {
                { 0, 0 }, { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 },
                { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 }
        };
        for (int[] offset : offsets) {
            GroundResult ground = groundBelow(selected.add(offset[0], 0, offset[1]), profile);
            if (ground.status == Tri.UNKNOWN) {
                unknown = true;
            } else if (ground.point != null) {
                return ground;
            }
        }
        return new GroundResult(unknown ? Tri.UNKNOWN : Tri.CLEAR, null);
    }

    private GroundResult groundBelow(Vec3 selected, NavigationProfile profile) {
        double minFeet = selected.y - TREE_GROUND_SEARCH_DEPTH;
        AABB probe = new AABB(selected.x + profile.localMinX(), minFeet + profile.localMinY() - .05,
                selected.z + profile.localMinZ(), selected.x + profile.localMaxX(),
                selected.y + profile.localMinY() + .05, selected.z + profile.localMaxZ());
        BoxSet boxes = collisionBoxes(probe);
        if (boxes.status != Tri.CLEAR) return new GroundResult(boxes.status, null);

        Vec3 best = null;
        for (AABB box : boxes.boxes) {
            if (!overlapsFootprint(box, selected.x, selected.z, profile)) continue;
            double feetY = box.maxY - profile.localMinY();
            if (feetY >= selected.y - EPS || feetY < minFeet) continue;
            BlockPos support = new BlockPos(floor(selected.x), floor(feetY - EPS), floor(selected.z));
            if (!hasChunk(support) || !hasChunk(support.below())) {
                return new GroundResult(Tri.UNKNOWN, null);
            }
            if (!stableGround(support)) continue;
            Tri body = bodyClear(new Vec3(selected.x, feetY, selected.z), profile);
            if (body == Tri.UNKNOWN) return new GroundResult(Tri.UNKNOWN, null);
            if (body == Tri.CLEAR && (best == null || feetY > best.y)) {
                best = new Vec3(selected.x, feetY, selected.z);
            }
        }
        return new GroundResult(Tri.CLEAR, best);
    }

    private boolean isTreeCanopy(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.LEAVES)
                || state.is(Blocks.SNOW) && level.getBlockState(pos.below()).is(BlockTags.LEAVES);
    }

    private boolean stableGround(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.SNOW)) state = level.getBlockState(pos.below());
        return !state.isAir() && !state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS);
    }

    private boolean hasChunk(BlockPos pos) {
        return level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    CenterResult horizontalCandidates(int blockX, int blockZ, double feetY,
                                      NavigationProfile profile) {
        double xc = blockX + 0.5;
        double zc = blockZ + 0.5;
        List<Vec3> centers = new ArrayList<>();
        centers.add(new Vec3(xc, feetY, zc));
        double bodyHeight = profile.localMaxY() - profile.localMinY();
        double inset = Math.min(0.02, bodyHeight * 0.25);
        double y0 = feetY + profile.localMinY() + inset;
        double y1 = feetY + profile.localMaxY() - inset;
        AABB query = new AABB(blockX + profile.localMinX() - 0.01, y0,
                blockZ + profile.localMinZ() - 0.01, blockX + 1.0 + profile.localMaxX() + 0.01,
                y1, blockZ + 1.0 + profile.localMaxZ() + 0.01);
        BoxSet set = collisionBoxes(query);
        if (set.status != Tri.CLEAR) return new CenterResult(set.status, centers);
        List<Double> xGaps = freeGaps(set.boxes, true, zc, profile, blockX, blockX + 1.0);
        List<Double> zGaps = freeGaps(set.boxes, false, xc, profile, blockZ, blockZ + 1.0);
        for (double x : xGaps) addCenter(centers, new Vec3(x, feetY, zc));
        for (double z : zGaps) addCenter(centers, new Vec3(xc, feetY, z));
        for (double x : xGaps) {
            for (double z : zGaps) addCenter(centers, new Vec3(x, feetY, z));
        }
        if (centers.size() > 6) centers = new ArrayList<>(centers.subList(0, 6));
        return new CenterResult(Tri.CLEAR, centers);
    }

    CenterResult waterCandidates(int blockX, int blockZ, double nearY,
                                 NavigationProfile profile) {
        CenterResult centers = horizontalCandidates(blockX, blockZ, nearY, profile);
        if (centers.status == Tri.UNKNOWN) {
            return new CenterResult(Tri.UNKNOWN, Collections.emptyList());
        }
        List<Vec3> result = new ArrayList<>();
        boolean unknown = false;
        int base = floor(nearY);
        for (int index = 0; index < centers.centers.size(); index++) {
            Vec3 center = centers.centers.get(index);
            boolean added = false;
            for (int by = base - 3; by <= base + 1; by++) {
                FluidResult probe = fluidProbe(center.x, by + 0.5, center.z);
                if (probe.status == Tri.UNKNOWN) {
                    unknown = true;
                    continue;
                }
                if (!probe.water || probe.lava) continue;
                Vec3 feet = new Vec3(center.x, by + 0.1, center.z);
                if (waterImmersion(feet, profile) != Tri.CLEAR) continue;
                result.add(feet);
                added = true;
            }
            if (index == 0 && added) break;
        }
        result.sort(Comparator.comparingDouble(feet -> Math.abs(feet.y - nearY)));
        if (result.size() > 4) result = new ArrayList<>(result.subList(0, 4));
        return new CenterResult(result.isEmpty() && unknown ? Tri.UNKNOWN : Tri.CLEAR, result);
    }

    Tri waterSegmentClear(Vec3 from, Vec3 to, NavigationProfile profile) {
        Tri sweep = segmentClear(from, to, profile);
        if (sweep != Tri.CLEAR) return sweep;
        Tri start = waterImmersion(from, profile);
        if (start != Tri.CLEAR) return start;
        Tri end = waterImmersion(to, profile);
        if (end != Tri.CLEAR) return end;
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        int x = floor(from.x);
        int y = floor(from.y);
        int z = floor(from.z);
        int endX = floor(to.x);
        int endY = floor(to.y);
        int endZ = floor(to.z);
        int stepX = dx > 0.0 ? 1 : dx < 0.0 ? -1 : 0;
        int stepY = dy > 0.0 ? 1 : dy < 0.0 ? -1 : 0;
        int stepZ = dz > 0.0 ? 1 : dz < 0.0 ? -1 : 0;
        double maxX = stepX == 0 ? Double.POSITIVE_INFINITY
                : ((stepX > 0 ? x + 1 : x) - from.x) / dx;
        double maxY = stepY == 0 ? Double.POSITIVE_INFINITY
                : ((stepY > 0 ? y + 1 : y) - from.y) / dy;
        double maxZ = stepZ == 0 ? Double.POSITIVE_INFINITY
                : ((stepZ > 0 ? z + 1 : z) - from.z) / dz;
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dx);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dy);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(dz);
        double t = 0.0;
        int guard = (Math.abs(endX - x) + Math.abs(endY - y) + Math.abs(endZ - z)) * 3 + 32;
        while (guard-- > 0) {
            double next = Math.min(1.0, Math.min(maxX, Math.min(maxY, maxZ)));
            if (next > t + EPS) {
                double mid = (t + next) * 0.5;
                Vec3 point = new Vec3(from.x + dx * mid, from.y + dy * mid, from.z + dz * mid);
                Tri water = waterImmersion(point, profile);
                if (water != Tri.CLEAR) return water;
            }
            if (x == endX && y == endY && z == endZ) break;
            if (next >= 1.0 - EPS) break;
            boolean moved = false;
            if (maxX <= next + EPS) {
                x += stepX;
                maxX += deltaX;
                moved = true;
            }
            if (maxY <= next + EPS) {
                y += stepY;
                maxY += deltaY;
                moved = true;
            }
            if (maxZ <= next + EPS) {
                z += stepZ;
                maxZ += deltaZ;
                moved = true;
            }
            if (!moved) break;
            t = next;
        }
        return Tri.CLEAR;
    }

    Tri waterImmersion(Vec3 feet, NavigationProfile profile) {
        if (!insideBuildHeight(feet, profile)) return Tri.BLOCKED;
        AABB body = profile.boxAt(feet);
        if (!insideBorder(body)) return Tri.BLOCKED;
        double torsoY = (body.minY + body.maxY) * 0.5;
        double footY = body.minY + 0.05;
        double headY = body.maxY - Math.min(0.05, (body.maxY - body.minY) * 0.2);
        FluidResult foot = fluidProbe(feet.x, footY, feet.z);
        FluidResult torso = fluidProbe(feet.x, torsoY, feet.z);
        FluidResult head = fluidProbe(feet.x, headY, feet.z);
        if (foot.status == Tri.UNKNOWN || torso.status == Tri.UNKNOWN
                || head.status == Tri.UNKNOWN) return Tri.UNKNOWN;
        if (foot.lava || torso.lava || head.lava) return Tri.BLOCKED;
        if (!foot.water || !torso.water) return Tri.BLOCKED;
        if (footY >= foot.top - 0.01) return Tri.BLOCKED;
        if (torsoY >= torso.top - 0.01) return Tri.BLOCKED;
        return Tri.CLEAR;
    }

    FluidResult fluidProbe(double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y, z);
        if (pos.getY() < minY || pos.getY() >= maxYExclusive) {
            return new FluidResult(Tri.CLEAR, false, false, pos.getY());
        }
        if (!chunksLoaded(pos.getX() - 1, pos.getX() + 1, pos.getZ() - 1, pos.getZ() + 1)) {
            return new FluidResult(Tri.UNKNOWN, false, false, pos.getY());
        }
        Cell cell = cell(pos);
        FluidState fluid = cell.fluid;
        boolean water = fluid.is(FluidTags.WATER);
        boolean lava = fluid.is(FluidTags.LAVA);
        double top = pos.getY() + (fluid.isEmpty() ? 0.0 : fluid.getHeight(level, pos));
        return new FluidResult(Tri.CLEAR, water, lava, top);
    }

    FluidResult waterSurfaceAt(Vec3 feet) {
        FluidResult base = fluidProbe(feet.x, feet.y + 0.02, feet.z);
        if (base.status != Tri.CLEAR || !base.water) return base;
        FluidResult top = base;
        for (int step = 1; step <= 3; step++) {
            FluidResult above = fluidProbe(feet.x, Math.floor(feet.y + 0.02) + step + 0.02, feet.z);
            if (above.status != Tri.CLEAR) return above;
            if (!above.water) break;
            top = above;
        }
        return top;
    }

    double waterDepthAt(Vec3 feet, double surfaceTop, NavigationProfile profile) {
        int blockY = floor(surfaceTop - 0.01);
        FluidResult below = fluidProbe(feet.x, blockY - 0.5, feet.z);
        if (below.status == Tri.CLEAR && below.water) return 2.0;
        AABB probe = new AABB(feet.x + profile.localMinX(), blockY, feet.z + profile.localMinZ(),
                feet.x + profile.localMaxX(), surfaceTop, feet.z + profile.localMaxZ());
        BoxSet boxes = collisionBoxes(probe);
        if (boxes.status != Tri.CLEAR) return surfaceTop - blockY;
        double floorY = blockY;
        for (AABB box : boxes.boxes) {
            if (box.maxY <= surfaceTop && box.maxY > floorY) floorY = box.maxY;
        }
        return surfaceTop - floorY;
    }

    private void requireServerThread() {
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("Navigation collision queries must run on the server thread");
        }
    }

    private boolean chunksLoaded(int minBX, int maxBX, int minBZ, int maxBZ) {
        int minCX = minBX >> 4;
        int maxCX = maxBX >> 4;
        int minCZ = minBZ >> 4;
        int maxCZ = maxBZ >> 4;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (!level.hasChunk(cx, cz)) return false;
            }
        }
        return true;
    }

    private Cell cell(BlockPos pos) {
        Cell cached = cells.get(pos);
        if (cached != null) return cached;
        BlockState state = level.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(level, pos, context);
        List<AABB> boxes = new ArrayList<>();
        for (AABB local : shape.toAabbs()) {
            boxes.add(local.move(pos.getX(), pos.getY(), pos.getZ()));
        }
        Cell cell = new Cell(boxes, level.getFluidState(pos));
        cells.put(pos.immutable(), cell);
        return cell;
    }

    private static AABB union(AABB first, AABB second) {
        return new AABB(Math.min(first.minX, second.minX), Math.min(first.minY, second.minY),
                Math.min(first.minZ, second.minZ), Math.max(first.maxX, second.maxX),
                Math.max(first.maxY, second.maxY), Math.max(first.maxZ, second.maxZ));
    }

    private static boolean segmentIntersects(AABB box, Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        if ((dx == 0 && (from.x <= box.minX || from.x >= box.maxX))
                || (dy == 0 && (from.y <= box.minY || from.y >= box.maxY))
                || (dz == 0 && (from.z <= box.minZ || from.z >= box.maxZ))) return false;

        // The bounds already carry the spatial tolerance. An epsilon on the time interval
        // would discard real overlaps as the length of the swept segment increases.
        double enterX = dx == 0 ? 0 : ((dx > 0 ? box.minX : box.maxX) - from.x) / dx;
        double leaveX = dx == 0 ? 1 : ((dx > 0 ? box.maxX : box.minX) - from.x) / dx;
        double enterY = dy == 0 ? 0 : ((dy > 0 ? box.minY : box.maxY) - from.y) / dy;
        double leaveY = dy == 0 ? 1 : ((dy > 0 ? box.maxY : box.minY) - from.y) / dy;
        double enterZ = dz == 0 ? 0 : ((dz > 0 ? box.minZ : box.maxZ) - from.z) / dz;
        double leaveZ = dz == 0 ? 1 : ((dz > 0 ? box.maxZ : box.minZ) - from.z) / dz;
        double enter = Math.max(0, Math.max(enterX, Math.max(enterY, enterZ)));
        double leave = Math.min(1, Math.min(leaveX, Math.min(leaveY, leaveZ)));
        return enter < leave;
    }

    private static boolean lineInterval(double origin, double target, double min, double max,
                                        double[] result) {
        double direction = target - origin;
        if (Math.abs(direction) < EPS) {
            if (origin < min - EPS || origin > max + EPS) return false;
            result[0] = 0.0;
            result[1] = 1.0;
            return true;
        }
        double t0 = (min - origin) / direction;
        double t1 = (max - origin) / direction;
        if (t0 > t1) {
            double swap = t0;
            t0 = t1;
            t1 = swap;
        }
        result[0] = Math.max(0.0, t0);
        result[1] = Math.min(1.0, t1);
        return result[0] < result[1] - EPS;
    }

    private static boolean overlapsFootprint(AABB box, double x, double z,
                                             NavigationProfile profile) {
        return box.maxX > x + profile.localMinX() + EPS
                && box.minX < x + profile.localMaxX() - EPS
                && box.maxZ > z + profile.localMinZ() + EPS
                && box.minZ < z + profile.localMaxZ() - EPS;
    }

    private static List<Double> freeGaps(List<AABB> boxes, boolean xAxis, double fixed,
                                         NavigationProfile profile, double min, double max) {
        double otherMin = fixed + (xAxis ? profile.localMinZ() : profile.localMinX());
        double otherMax = fixed + (xAxis ? profile.localMaxZ() : profile.localMaxX());
        double lowOffset = xAxis ? profile.localMaxX() : profile.localMaxZ();
        double highOffset = xAxis ? profile.localMinX() : profile.localMinZ();
        List<double[]> blocked = new ArrayList<>();
        for (AABB box : boxes) {
            double boxOtherMin = xAxis ? box.minZ : box.minX;
            double boxOtherMax = xAxis ? box.maxZ : box.maxX;
            if (boxOtherMax <= otherMin + EPS || boxOtherMin >= otherMax - EPS) continue;
            double low = (xAxis ? box.minX : box.minZ) - lowOffset;
            double high = (xAxis ? box.maxX : box.maxZ) - highOffset;
            double start = Math.max(min, low);
            double end = Math.min(max, high);
            if (start < end - EPS) blocked.add(new double[] { start, end });
        }
        blocked.sort(Comparator.comparingDouble(interval -> interval[0]));
        List<Double> gaps = new ArrayList<>();
        double cursor = min;
        for (double[] interval : blocked) {
            if (interval[0] > cursor + 0.03) gaps.add((cursor + interval[0]) * 0.5);
            if (interval[1] > cursor) cursor = interval[1];
        }
        if (max > cursor + 0.03) gaps.add((cursor + max) * 0.5);
        return gaps;
    }

    private static void addCenter(List<Vec3> centers, Vec3 candidate) {
        for (Vec3 existing : centers) {
            if (existing.distanceToSqr(candidate) < 1.0E-8) return;
        }
        centers.add(candidate);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
