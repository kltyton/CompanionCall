package com.kltyton.companioncall.navigation;

import com.kltyton.companioncall.api.TravelMode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Collision, support, mode-transition and path-shortcut predicates for one session. */
final class NavigationGeometry {
    private static final int MAX_SHORTCUT_POINTS = 16;
    private static final double EPS = 1.0E-5;

    static final class Resolution {
        final CollisionWorld.Tri status;
        final Vec3 point;
        final TravelMode mode;

        Resolution(CollisionWorld.Tri status, Vec3 point, TravelMode mode) {
            this.status = status;
            this.point = point;
            this.mode = mode;
        }
    }

    static final class Start {
        final Vec3 point;
        final TravelMode mode;

        Start(Vec3 point, TravelMode mode) {
            this.point = point;
            this.mode = mode;
        }
    }

    private final Entity entity;
    private final CollisionWorld world;

    NavigationGeometry(Entity entity, CollisionWorld world) {
        this.entity = entity;
        this.world = world;
    }

    Start resolveStart(NavigationProfile profile) {
        Vec3 position = entity.position();
        boolean unknown = false;
        if (profile.allows(TravelMode.WATER)) {
            CollisionWorld.Tri water = world.waterImmersion(position, profile);
            if (water == CollisionWorld.Tri.CLEAR) return new Start(position, TravelMode.WATER);
            if (water == CollisionWorld.Tri.UNKNOWN) unknown = true;
        }
        if (profile.allows(TravelMode.LAND)) {
            CollisionWorld.StandResult stands = world.standHeights(entity.getX(), entity.getZ(),
                    entity.getY(), profile);
            if (stands.status == CollisionWorld.Tri.UNKNOWN) {
                unknown = true;
            } else if (stands.status == CollisionWorld.Tri.CLEAR && !stands.heights.isEmpty()) {
                return new Start(new Vec3(entity.getX(), stands.heights.get(0), entity.getZ()),
                        TravelMode.LAND);
            }
        }
        if (profile.allows(TravelMode.AIR)) {
            CollisionWorld.Tri body = world.bodyClear(position, profile);
            if (body == CollisionWorld.Tri.CLEAR) return new Start(position, TravelMode.AIR);
            if (body == CollisionWorld.Tri.UNKNOWN) unknown = true;
            if (profile.modes().size() == 1) return new Start(position, TravelMode.AIR);
        }
        if (unknown) return null;
        if (profile.allows(TravelMode.LAND)) return new Start(position, TravelMode.LAND);
        return new Start(position, profile.primaryMode());
    }

    Resolution resolveGoal(NavigationProfile profile, Vec3 requested) {
        if (requested == null) return new Resolution(CollisionWorld.Tri.BLOCKED, null, null);
        CollisionWorld.Tri unknown = null;
        Vec3 landFallback = null;
        if (profile.allows(TravelMode.WATER)) {
            CollisionWorld.Tri water = world.waterImmersion(requested, profile);
            if (water == CollisionWorld.Tri.CLEAR) {
                return new Resolution(CollisionWorld.Tri.CLEAR, requested, TravelMode.WATER);
            }
            if (water == CollisionWorld.Tri.UNKNOWN) unknown = CollisionWorld.Tri.UNKNOWN;
        }
        if (profile.allows(TravelMode.LAND)) {
            int centerX = floor(requested.x);
            int centerZ = floor(requested.z);
            for (int radius = 0; radius <= 1; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (radius == 1 && Math.abs(dx) != 1 && Math.abs(dz) != 1) continue;
                        double x = radius == 0 ? requested.x : centerX + dx + 0.5;
                        double z = radius == 0 ? requested.z : centerZ + dz + 0.5;
                        CollisionWorld.StandResult stands = world.standHeights(x, z,
                                requested.y, profile);
                        if (stands.status == CollisionWorld.Tri.UNKNOWN) {
                            unknown = CollisionWorld.Tri.UNKNOWN;
                            continue;
                        }
                        if (stands.status != CollisionWorld.Tri.CLEAR
                                || stands.heights.isEmpty()) continue;
                        Vec3 point = new Vec3(x, stands.heights.get(0), z);
                        if (landFallback == null) landFallback = point;
                        if (Math.abs(point.y - requested.y) <= arrivalRadius(TravelMode.LAND)) {
                            return new Resolution(CollisionWorld.Tri.CLEAR, point,
                                    TravelMode.LAND);
                        }
                    }
                }
            }
            if (!profile.allows(TravelMode.AIR) && landFallback != null) {
                return new Resolution(CollisionWorld.Tri.CLEAR, landFallback, TravelMode.LAND);
            }
        }
        if (profile.allows(TravelMode.AIR)) {
            CollisionWorld.Tri body = world.bodyClear(requested, profile);
            if (body == CollisionWorld.Tri.CLEAR) {
                return new Resolution(CollisionWorld.Tri.CLEAR, requested, TravelMode.AIR);
            }
            if (body == CollisionWorld.Tri.UNKNOWN) unknown = CollisionWorld.Tri.UNKNOWN;
        }
        if (landFallback != null) {
            return new Resolution(CollisionWorld.Tri.CLEAR, landFallback, TravelMode.LAND);
        }
        return new Resolution(unknown == null ? CollisionWorld.Tri.BLOCKED : unknown, null, null);
    }

    CollisionWorld.Tri validateSegment(NavigationProfile profile, NavigationPath path, int index,
                                       NavigationPath.Segment segment) {
        boolean landKind = segment.kind() == NavigationPath.Kind.WALK
                || segment.kind() == NavigationPath.Kind.STEP_UP
                || segment.kind() == NavigationPath.Kind.JUMP
                || segment.kind() == NavigationPath.Kind.DROP;
        if (landKind) {
            boolean transition;
            if (index > 0) {
                NavigationPath.Kind previous = path.segments().get(index - 1).kind();
                transition = previous == NavigationPath.Kind.SWIM
                        || previous == NavigationPath.Kind.FLY;
            } else {
                transition = currentMode(profile) != TravelMode.LAND;
            }
            if (transition) {
                return transitionLandClear(profile, segment.from(), segment.to(),
                        segment.kind());
            }
        }
        return validateDirect(profile, segment.from(), segment.to(), segment.kind());
    }

    CollisionWorld.Tri validateDirect(NavigationProfile profile, Vec3 from, Vec3 to,
                                      NavigationPath.Kind kind) {
        if (kind == NavigationPath.Kind.WALK) {
            CollisionWorld.Tri clear = world.segmentClear(from, to, profile);
            if (clear != CollisionWorld.Tri.CLEAR) return clear;
            return world.groundedSegmentResult(from, to, profile);
        }
        if (kind == NavigationPath.Kind.STEP_UP) return world.stepClear(from, to, profile);
        if (kind == NavigationPath.Kind.JUMP) return world.jumpClear(from, to, profile);
        if (kind == NavigationPath.Kind.DROP) return world.dropClear(from, to, profile);
        if (kind == NavigationPath.Kind.SWIM) {
            return world.waterSegmentClear(from, to, profile);
        }
        CollisionWorld.Tri clear = world.segmentClear(from, to, profile);
        if (clear != CollisionWorld.Tri.CLEAR) return clear;
        return world.bodyClear(to, profile);
    }

    CollisionWorld.Tri transitionLandClear(NavigationProfile profile, Vec3 from, Vec3 to,
                                           NavigationPath.Kind kind) {
        if (kind == NavigationPath.Kind.WALK) return world.segmentClear(from, to, profile);
        if (kind == NavigationPath.Kind.STEP_UP) return world.stepClear(from, to, profile);
        if (kind == NavigationPath.Kind.JUMP) return world.jumpClear(from, to, profile);
        return world.dropClear(from, to, profile);
    }

    CollisionWorld.Tri arrivalClear(NavigationProfile profile, Vec3 from, Vec3 to,
                                    TravelMode mode) {
        if (mode == TravelMode.LAND) {
            NavigationPath.Kind kind = classify(profile, from, to);
            if (kind == null) return CollisionWorld.Tri.BLOCKED;
            return validateDirect(profile, from, to, kind);
        }
        if (mode == TravelMode.WATER) return world.waterSegmentClear(from, to, profile);
        CollisionWorld.Tri clear = world.segmentClear(from, to, profile);
        if (clear != CollisionWorld.Tri.CLEAR) return clear;
        return world.bodyClear(to, profile);
    }

    TravelMode currentMode(NavigationProfile profile) {
        Vec3 position = entity.position();
        if (profile.allows(TravelMode.WATER)
                && world.waterImmersion(position, profile) == CollisionWorld.Tri.CLEAR) {
            return TravelMode.WATER;
        }
        if (profile.allows(TravelMode.AIR) && !entity.onGround()) return TravelMode.AIR;
        if (profile.allows(TravelMode.LAND)) {
            CollisionWorld.StandResult stands = world.standHeights(entity.getX(), entity.getZ(),
                    entity.getY(), profile);
            if (stands.status == CollisionWorld.Tri.CLEAR && !stands.heights.isEmpty()) {
                return TravelMode.LAND;
            }
        }
        if (profile.allows(TravelMode.AIR)) return TravelMode.AIR;
        return profile.primaryMode();
    }

    boolean canMoveDirectlyTo(NavigationProfile profile, Vec3 target) {
        if (target == null || !Double.isFinite(target.x) || !Double.isFinite(target.y)
                || !Double.isFinite(target.z)) return false;
        world.beginStep();
        TravelMode targetMode = exactTargetMode(profile, target);
        if (targetMode == null) return false;
        Vec3 from = entity.position();
        TravelMode current = currentMode(profile);
        if (current == targetMode) {
            return arrivalClear(profile, from, target, targetMode)
                    == CollisionWorld.Tri.CLEAR;
        }
        if (targetMode == TravelMode.WATER && current == TravelMode.LAND) {
            NavigationPath.Kind kind = classify(profile, from, target);
            if (kind == null) return false;
            CollisionWorld.Tri clear = validateDirect(profile, from, target, kind);
            return clear == CollisionWorld.Tri.CLEAR
                    && world.waterImmersion(target, profile) == CollisionWorld.Tri.CLEAR;
        }
        if (targetMode == TravelMode.LAND
                && (current == TravelMode.WATER || current == TravelMode.AIR)) {
            NavigationPath.Kind kind = classify(profile, from, target);
            if (kind == null) return false;
            return transitionLandClear(profile, from, target, kind)
                    == CollisionWorld.Tri.CLEAR;
        }
        if (targetMode == TravelMode.AIR && current == TravelMode.LAND) {
            return world.segmentClear(from, target, profile) == CollisionWorld.Tri.CLEAR
                    && world.bodyClear(target, profile) == CollisionWorld.Tri.CLEAR;
        }
        return false;
    }

    TravelMode exactTargetMode(NavigationProfile profile, Vec3 target) {
        if (profile.allows(TravelMode.WATER)
                && world.waterImmersion(target, profile) == CollisionWorld.Tri.CLEAR) {
            return TravelMode.WATER;
        }
        if (profile.allows(TravelMode.LAND)) {
            CollisionWorld.StandResult stands = world.standHeights(target.x, target.z, target.y,
                    profile);
            if (stands.status == CollisionWorld.Tri.CLEAR) {
                for (double height : stands.heights) {
                    if (Math.abs(height - target.y) <= 0.05
                            && world.bodyClear(target, profile) == CollisionWorld.Tri.CLEAR
                            && world.bodyClear(new Vec3(target.x, height, target.z), profile)
                            == CollisionWorld.Tri.CLEAR) {
                        return TravelMode.LAND;
                    }
                }
            }
        }
        if (profile.allows(TravelMode.AIR)
                && world.bodyClear(target, profile) == CollisionWorld.Tri.CLEAR) {
            return TravelMode.AIR;
        }
        return null;
    }

    NavigationPath smooth(NavigationProfile profile, NavigationPath input) {
        if (input.size() < 3) return input;
        world.beginStep();
        NavigationPath land = smoothLand(profile, input);
        NavigationPath air = smoothRun(profile, land, NavigationPath.Kind.FLY);
        return smoothRun(profile, air, NavigationPath.Kind.SWIM);
    }

    private NavigationPath smoothLand(NavigationProfile profile, NavigationPath input) {
        List<NavigationPath.Point> source = input.points();
        List<NavigationPath.Point> result = new ArrayList<>();
        result.add(source.get(0));
        int index = 0;
        while (index < source.size() - 1) {
            int far = index + 1;
            boolean afterModeSwitch = index == 0 && profile.modes().size() > 1;
            if (index > 0) {
                NavigationPath.Kind previous = source.get(index - 1).incoming();
                afterModeSwitch = previous == NavigationPath.Kind.SWIM
                        || previous == NavigationPath.Kind.FLY;
            }
            int limit = Math.min(source.size() - 1, index + MAX_SHORTCUT_POINTS);
            if (!afterModeSwitch) {
                for (int candidate = limit; candidate >= index + 2; candidate--) {
                    double y = source.get(index).feet().y;
                    boolean flat = true;
                    for (int k = index + 1; k <= candidate; k++) {
                        if (Math.abs(source.get(k).feet().y - y) > 0.02
                                || source.get(k).incoming() != NavigationPath.Kind.WALK) {
                            flat = false;
                            break;
                        }
                    }
                    if (!flat) continue;
                    Vec3 from = source.get(index).feet();
                    Vec3 to = source.get(candidate).feet();
                    if (world.segmentClear(from, to, profile) != CollisionWorld.Tri.CLEAR) continue;
                    if (world.groundedSegmentResult(from, to, profile)
                            != CollisionWorld.Tri.CLEAR) continue;
                    far = candidate;
                    break;
                }
            }
            NavigationPath.Point selected = source.get(far);
            result.add(new NavigationPath.Point(selected.feet(),
                    far == index + 1 ? selected.incoming() : NavigationPath.Kind.WALK));
            index = far;
        }
        return NavigationPath.fromPoints(result);
    }

    private NavigationPath smoothRun(NavigationProfile profile, NavigationPath input,
                                     NavigationPath.Kind runKind) {
        List<NavigationPath.Point> source = input.points();
        List<NavigationPath.Point> result = new ArrayList<>();
        result.add(source.get(0));
        int index = 0;
        while (index < source.size() - 1) {
            int far = index + 1;
            if (source.get(index + 1).incoming() == runKind
                    && source.get(index + 1).feet().distanceToSqr(source.get(index).feet())
                    > 1.0E-8) {
                int limit = Math.min(source.size() - 1, index + MAX_SHORTCUT_POINTS);
                for (int candidate = limit; candidate >= index + 2; candidate--) {
                    boolean sameRun = true;
                    for (int k = index + 1; k <= candidate; k++) {
                        if (source.get(k).incoming() != runKind) {
                            sameRun = false;
                            break;
                        }
                    }
                    if (!sameRun) continue;
                    Vec3 from = source.get(index).feet();
                    Vec3 to = source.get(candidate).feet();
                    CollisionWorld.Tri clear = runKind == NavigationPath.Kind.FLY
                            ? validateDirect(profile, from, to, NavigationPath.Kind.FLY)
                            : world.waterSegmentClear(from, to, profile);
                    if (clear != CollisionWorld.Tri.CLEAR) continue;
                    far = candidate;
                    break;
                }
            }
            NavigationPath.Point selected = source.get(far);
            result.add(new NavigationPath.Point(selected.feet(),
                    far == index + 1 ? selected.incoming() : runKind));
            index = far;
        }
        return NavigationPath.fromPoints(result);
    }

    static NavigationPath.Kind kindForMode(NavigationProfile profile, Vec3 from, Vec3 to,
                                           TravelMode mode) {
        if (mode == TravelMode.WATER) return NavigationPath.Kind.SWIM;
        if (mode == TravelMode.AIR) return NavigationPath.Kind.FLY;
        return classify(profile, from, to);
    }

    static NavigationPath.Kind classify(NavigationProfile profile, Vec3 from, Vec3 to) {
        double dy = to.y - from.y;
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

    static double arrivalRadius(TravelMode mode) {
        return mode == TravelMode.LAND ? 0.9 : 1.5;
    }

    static double arrivalHoldRadius(TravelMode mode) {
        return mode == TravelMode.LAND ? 0.3 : 0.5;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
