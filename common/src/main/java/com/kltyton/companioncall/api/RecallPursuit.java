package com.kltyton.companioncall.api;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import com.kltyton.companioncall.platform.NativeVersion;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A server-observed interception plan. Speeds are blocks per tick, not AI speed multipliers. */
public record RecallPursuit(Vec3 position, Vec3 target, Vec3 velocity, double speed,
                            int leadTicks, boolean falling, boolean grounded) {
    private static final int MAX_LEAD_TICKS = 40;
    private static final double NORMAL_ACCELERATION = .18;
    private static final double CATCH_ACCELERATION = .35;

    public static RecallPursuit legacy(Entity pet, ServerPlayer owner, double nativeMultiplier) {
        var adapter = CompanionCallApi.adapter(pet);
        Set<TravelMode> modes = adapter.travelModes(pet);
        RecallPursuit plan = predict(pet, owner, NativeVersion.knownMovement(owner, owner.getDeltaMovement()), modes);
        return new RecallPursuit(plan.position, plan.target, plan.velocity,
                nativeMultiplier * nativeBaseSpeed(pet), plan.leadTicks, plan.falling, plan.grounded);
    }

    public double legacyMultiplier(Entity entity) {
        return nativeMultiplier(entity, speed);
    }

    public double nativeMultiplier(Entity entity, double physicalSpeed) {
        return physicalSpeed / nativeBaseSpeed(entity);
    }

    private static double nativeBaseSpeed(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return 1;
        var flying = living.getAttribute(Attributes.FLYING_SPEED);
        double value = !entity.onGround() && flying != null ? flying.getValue() : NativeVersion.speed(living);
        return Math.max(.01, value);
    }

    /** Compatibility overload; sampled position acceleration is replaced by native vertical forces. */
    public static RecallPursuit predict(Entity pet, ServerPlayer owner, Vec3 velocity,
                                        Vec3 acceleration, Set<TravelMode> modes) {
        return predict(pet, owner, velocity, modes);
    }

    /** Position differences are not sustained forces; forecast vertical motion through native gravity. */
    public static RecallPursuit predict(Entity pet, ServerPlayer owner, Vec3 velocity, Set<TravelMode> modes) {
        return predict(pet, owner, velocity, modes, 0);
    }

    public static RecallPursuit predict(Entity pet, ServerPlayer owner, Vec3 velocity, Set<TravelMode> modes,
                                        double remainingRouteDistance) {
        boolean air = modes.contains(TravelMode.AIR);
        boolean falling = !owner.onGround() && velocity.y < -0.01 && !owner.isInWater()
                && !owner.isFallFlying() && !owner.getAbilities().flying;
        boolean catching = falling;
        double normalSpeed = air ? 1.2 : modes.contains(TravelMode.WATER) ? .6 : .8;
        double normalMaximum = air ? 1.8 : modes.contains(TravelMode.WATER) ? 1 : 1.4;
        double distanceBoost = Math.min(1, Math.max(0, (remainingRouteDistance - 8) / 80));
        normalSpeed *= 1 + distanceBoost;
        normalMaximum *= 1 + distanceBoost;
        double ownerSpeed = air ? velocity.length() : Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
        double maximumSpeed = air && catching
                ? Math.max(5, ownerSpeed + normalMaximum + remainingRouteDistance / MAX_LEAD_TICKS) : normalMaximum;
        double minimumSpeed = Math.min(maximumSpeed, Math.max(normalSpeed, ownerSpeed));
        double accelerationLimit = catching ? CATCH_ACCELERATION : NORMAL_ACCELERATION;
        Vec3 position = owner.position();
        Vec3 predicted = position;
        Vec3 motion = velocity;
        AABB box = owner.getBoundingBox();
        boolean ballistic = !owner.onGround() && !owner.isInWater() && !owner.isFallFlying()
                && !owner.getAbilities().flying && !owner.isNoGravity();
        Vec3 actualMotion = pet.position().subtract(new Vec3(pet.xo, pet.yo, pet.zo));
        if (!air) actualMotion = new Vec3(actualMotion.x, 0, actualMotion.z);
        int ticks = 0;
        for (int tick = 1; tick <= MAX_LEAD_TICKS; tick++) {
            if (!loaded(owner, box.expandTowards(motion))) break;
            Vec3 step = Entity.collideBoundingBox(owner, motion, box, owner.level(), List.of());
            predicted = predicted.add(step);
            box = box.move(step);
            motion = new Vec3(Math.abs(step.x - motion.x) < 1.0e-6 ? motion.x : 0,
                    Math.abs(step.y - motion.y) < 1.0e-6
                            ? (ballistic ? NativeVersion.nextAirVelocity(owner, motion.y) : motion.y) : 0,
                    Math.abs(step.z - motion.z) < 1.0e-6 ? motion.z : 0);
            ticks = tick;
            Vec3 separation = predicted.subtract(pet.position());
            if (!air) separation = new Vec3(separation.x, 0, separation.z);
            double distance = separation.length();
            double initialSpeed = distance < 1.0e-6 ? 0 : actualMotion.dot(separation.scale(1 / distance));
            if (distance <= reachableDistance(tick, maximumSpeed, initialSpeed, accelerationLimit)) {
                double lower = minimumSpeed;
                double upper = maximumSpeed;
                for (int iteration = 0; iteration < 12; iteration++) {
                    double candidate = (lower + upper) * .5;
                    if (reachableDistance(tick, candidate, initialSpeed, accelerationLimit) >= distance) upper = candidate;
                    else lower = candidate;
                }
                return new RecallPursuit(position, predicted, velocity, upper, tick, falling, owner.onGround());
            }
        }
        return new RecallPursuit(position, predicted, velocity, maximumSpeed, ticks, falling, owner.onGround());
    }

    private static double reachableDistance(int ticks, double cruiseSpeed, double initialSpeed, double acceleration) {
        double travelled = 0;
        double speed = initialSpeed;
        for (int tick = 0; tick < ticks; tick++) {
            speed += Math.max(-acceleration, Math.min(acceleration, cruiseSpeed - speed));
            travelled += speed;
        }
        return travelled;
    }

    /** The continuously refreshed interception target, adjusted for the actual seat. */
    public Vec3 targetFor(Vec3 mountPosition, Vec3 riderOffset) {
        if (grounded) riderOffset = new Vec3(riderOffset.x, 0, riderOffset.z);
        return target.subtract(riderOffset);
    }

    public Vec3 currentTargetFor(Vec3 riderOffset) {
        if (grounded) riderOffset = new Vec3(riderOffset.x, 0, riderOffset.z);
        return position.subtract(riderOffset);
    }

    /** Keeps the owner's velocity in the final phase, then removes relative closing speed. */
    public Vec3 velocityToward(Vec3 mountPosition, Vec3 waypoint, boolean finalApproach) {
        return velocityToward(mountPosition, waypoint, finalApproach, Vec3.ZERO);
    }

    public Vec3 velocityToward(Vec3 mountPosition, Vec3 waypoint, boolean finalApproach, Vec3 riderOffset) {
        return velocityTowardPoint(mountPosition, finalApproach ? currentTargetFor(riderOffset) : waypoint, finalApproach);
    }

    public Vec3 velocityTowardPoint(Vec3 mountPosition, Vec3 target, boolean finalApproach) {
        Vec3 delta = target.subtract(mountPosition);
        double distance = delta.length();
        double closing = finalApproach ? Math.min(speed, Math.sqrt(2 * accelerationLimit() * distance)) : speed;
        Vec3 result = distance < 1.0e-6 ? Vec3.ZERO : delta.scale(closing / distance);
        if (finalApproach) result = result.add(velocity);
        double length = result.length();
        return length > speed ? result.scale(speed / length) : result;
    }

    public double accelerationLimit() {
        return falling ? CATCH_ACCELERATION : NORMAL_ACCELERATION;
    }

    private static boolean loaded(ServerPlayer owner, AABB sweep) {
        if (!owner.level().getWorldBorder().isWithinBounds(sweep)) return false;
        int minX = (int) Math.floor(sweep.minX) >> 4;
        int maxX = (int) Math.floor(sweep.maxX - 1.0e-6) >> 4;
        int minZ = (int) Math.floor(sweep.minZ) >> 4;
        int maxZ = (int) Math.floor(sweep.maxZ - 1.0e-6) >> 4;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!owner.level().hasChunkAt(new BlockPos(x << 4, (int) sweep.minY, z << 4))) return false;
            }
        }
        return true;
    }
}
