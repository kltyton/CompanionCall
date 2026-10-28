package com.kltyton.companioncall.world.recall;

import com.kltyton.companioncall.api.TravelMode;
import com.kltyton.companioncall.api.CompanionCallApi;
import com.kltyton.companioncall.platform.NativeVersion;
import java.util.Set;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class LandingFinder {
    public record Landing(Vec3 position, boolean protectedArrival) {}

    public static final class NoLandingSpace extends RuntimeException {
        private NoLandingSpace() { super("No collision-free recall landing is available"); }
    }

    private LandingFinder() {}

    public static Optional<Landing> boarding(Entity pet, ServerPlayer owner, Set<TravelMode> modes, Vec3 riderPosition) {
        var adapter = CompanionCallApi.adapter(pet);
        if (adapter == null || !adapter.canRide(pet, owner)) return Optional.empty();
        var level = (ServerLevel) owner.level();
        Vec3 target = !owner.onGround() ? riderPosition.subtract(adapter.boardingOffset(pet, owner)) : riderPosition;
        if (safe(level, pet, target, modes)) return Optional.of(new Landing(target, false));
        if (safe(level, pet, target, modes, true)) return Optional.of(new Landing(target, true));
        return Optional.empty();
    }

    /** Remote recalls enter behind the owner, then finish through normal navigation. */
    public static Landing findForRecall(Entity pet, ServerPlayer owner, Set<TravelMode> modes) {
        var level = (ServerLevel) owner.level();
        Vec3 behind = Vec3.directionFromRotation(0, owner.getYRot()).scale(-1);
        for (int radius : new int[]{20, 16, 24}) {
            for (int angle : new int[]{0, 30, -30, 60, -60}) {
                Vec3 offset = behind.yRot((float) Math.toRadians(angle)).scale(radius);
                for (int dy = modes.contains(TravelMode.AIR) ? 6 : 4; dy >= -8; dy--) {
                    Vec3 target = owner.position().add(offset.x, dy, offset.z);
                    BlockPos below = BlockPos.containing(target).below();
                    if (!level.hasChunkAt(below)) continue;
                    if (modes.contains(TravelMode.LAND) && !modes.contains(TravelMode.AIR)) {
                        var shape = level.getBlockState(below).getCollisionShape(level, below);
                        if (shape.isEmpty()) continue;
                        target = new Vec3(target.x, below.getY() + shape.max(Direction.Axis.Y), target.z);
                    }
                    if (safe(level, pet, target, modes)) return new Landing(target, false);
                }
            }
        }
        return find(pet, owner, modes);
    }

    public static Landing find(Entity pet, ServerPlayer owner, Set<TravelMode> modes) {
        var level = (ServerLevel) owner.level();
        var center = owner.blockPosition();
        for (int radius = 1; radius <= 6; radius++) {
            for (int dy = 2; dy >= -4; dy--) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        var target = new Vec3(center.getX() + dx + 0.5, center.getY() + dy, center.getZ() + dz + 0.5);
                        if (modes.contains(TravelMode.LAND) && !modes.contains(TravelMode.AIR)) {
                            var support = BlockPos.containing(target).below();
                            if (!level.hasChunkAt(support)) continue;
                            var shape = level.getBlockState(support).getCollisionShape(level, support);
                            if (!shape.isEmpty()) target = new Vec3(target.x, support.getY() + shape.max(Direction.Axis.Y), target.z);
                        }
                        if (safe(level, pet, target, modes)) return new Landing(target, false);
                        if (modes.contains(TravelMode.LAND)) {
                            var support = BlockPos.containing(target).below();
                            if (level.hasChunkAt(support)) {
                                var shape = level.getBlockState(support).getCollisionShape(level, support);
                                if (!shape.isEmpty()) {
                                    var surface = new Vec3(target.x, support.getY() + shape.max(Direction.Axis.Y), target.z);
                                    if (safe(level, pet, surface, modes)) return new Landing(surface, false);
                                }
                            }
                        }
                    }
                }
            }
        }
        for (int dy = 0; dy <= 8; dy++) {
            Vec3 target = owner.position().add(0, dy, 0);
            if (safe(level, pet, target, modes, true)) return new Landing(target, true);
        }
        throw new NoLandingSpace();
    }

    private static boolean safe(ServerLevel level, Entity pet, Vec3 target, Set<TravelMode> modes) {
        return safe(level, pet, target, modes, false);
    }

    private static boolean safe(ServerLevel level, Entity pet, Vec3 target, Set<TravelMode> modes, boolean unsupported) {
        var box = pet.getBoundingBox().move(target.subtract(pet.position()));
        for (int x = (int) Math.floor(box.minX) >> 4; x <= ((int) Math.floor(box.maxX - 1.0e-6) >> 4); x++) {
            for (int z = (int) Math.floor(box.minZ) >> 4; z <= ((int) Math.floor(box.maxZ - 1.0e-6) >> 4); z++) {
                if (!level.hasChunkAt(new BlockPos(x << 4, (int) box.minY, z << 4))) return false;
            }
        }
        if (box.minY < NativeVersion.minY(level) || box.maxY >= NativeVersion.maxY(level)
                || !level.getWorldBorder().isWithinBounds(box)
                || !level.hasChunkAt(BlockPos.containing(target)) || !level.noCollision(pet, box)) return false;
        boolean entirelyWater = true;
        boolean anyFluid = false;
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX - 1.0e-6); x++) {
            for (int y = (int) Math.floor(box.minY); y <= (int) Math.floor(box.maxY - 1.0e-6); y++) {
                for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ - 1.0e-6); z++) {
                    var pos = new BlockPos(x, y, z);
                    var state = level.getBlockState(pos);
                    if (dangerous(state)) return false;
                    var fluid = state.getFluidState();
                    if (!fluid.isEmpty() && !fluid.is(FluidTags.WATER)) return false;
                    entirelyWater &= fluid.is(FluidTags.WATER)
                            && Math.min(box.maxY, y + 1) <= y + fluid.getHeight(level, pos) + 1.0e-6;
                    anyFluid |= !fluid.isEmpty();
                }
            }
        }
        if (entirelyWater && modes.contains(TravelMode.WATER)) return true;
        if (anyFluid) return false;
        if (modes.contains(TravelMode.AIR) || unsupported) return true;
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX - 1.0e-6); x++) {
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ - 1.0e-6); z++) {
                var support = level.getBlockState(new BlockPos(x, (int) Math.floor(box.minY - 0.01), z));
                if (support.is(Blocks.MAGMA_BLOCK) || support.is(Blocks.CAMPFIRE)
                        || support.is(Blocks.SOUL_CAMPFIRE) || support.is(Blocks.CACTUS)
                        || support.is(Blocks.POINTED_DRIPSTONE)) return false;
            }
        }
        if (!modes.contains(TravelMode.LAND)) return false;
        for (int x = (int) Math.floor(box.minX); x <= (int) Math.floor(box.maxX - 1.0e-6); x++) {
            for (int z = (int) Math.floor(box.minZ); z <= (int) Math.floor(box.maxZ - 1.0e-6); z++) {
                var support = new BlockPos(x, (int) Math.floor(box.minY - 0.05), z);
                var state = level.getBlockState(support);
                if (dangerous(state) || !state.getFluidState().isEmpty()) return false;
            }
        }
        return level.getBlockCollisions(pet, box.move(0, -0.15, 0)).iterator().hasNext();
    }

    private static boolean dangerous(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.CACTUS)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.POWDER_SNOW);
    }
}
