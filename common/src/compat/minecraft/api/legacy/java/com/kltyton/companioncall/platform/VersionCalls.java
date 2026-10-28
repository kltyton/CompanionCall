package com.kltyton.companioncall.platform;

import java.util.UUID;
import com.kltyton.companioncall.api.OwnerChangeResult;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.server.level.TicketType;
import com.kltyton.companioncall.storage.transfer.LegacyTeleportTarget;

public final class VersionCalls {
    public static void setPathMode(net.minecraft.world.level.pathfinder.Node node, com.kltyton.companioncall.api.TravelMode mode) {
        node.type = switch (mode) {
            case LAND -> net.minecraft.world.level.pathfinder.BlockPathTypes.WALKABLE;
            case AIR -> net.minecraft.world.level.pathfinder.BlockPathTypes.OPEN;
            case WATER -> net.minecraft.world.level.pathfinder.BlockPathTypes.WATER;
        };
    }
    public static void synchronizeMovement(Entity entity) { entity.hasImpulse = true; }
    // 1.20.1 has no known-movement API; the caller measures displacement over elapsed server ticks.
    public static Vec3 knownMovement(ServerPlayer player, Vec3 measured) { return measured; }
    public static double nextAirVelocity(LivingEntity entity, double velocity) {
        var levitation = entity.getEffect(net.minecraft.world.effect.MobEffects.LEVITATION);
        if (levitation != null) velocity += (.05 * (levitation.getAmplifier() + 1) - velocity) * .2;
        else {
            double gravity = entity.isNoGravity() ? 0 : NativeGravity.value(entity);
            if (velocity <= 0 && entity.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING)) gravity = Math.min(gravity, .01);
            velocity -= gravity;
        }
        return velocity * .98;
    }
    private static final TicketType<UUID> RECALL = TicketType.create("companion_call", java.util.Comparator.comparing(UUID::toString));
    private static final SoundEvent WHISTLE = SoundEvent.createVariableRangeEvent(id("companion_call:whistle"));
    private VersionCalls() {}
    public static boolean canManage(CommandSourceStack source) {
        return source.hasPermission(2);
    }
    public static OwnerChangeResult setOwner(Entity entity, ServerPlayer owner) {
        if (entity instanceof TamableAnimal pet) {
            if (pet.isTame()) pet.setOwnerUUID(owner.getUUID());
            else pet.tame(owner);
        } else if (entity instanceof AbstractHorse horse) {
            if (horse.isTamed()) horse.setOwnerUUID(owner.getUUID());
            else horse.tameWithName(owner);
        } else return OwnerChangeResult.UNSUPPORTED;
        return owner.getUUID().equals(owner(entity)) ? OwnerChangeResult.SUCCESS : OwnerChangeResult.REJECTED;
    }
    public static ResourceLocation id(String name) { return new ResourceLocation(name); }
    public static String dimension(Level level) { return level.dimension().location().toString(); }
    public static ResourceKey<Level> levelKey(String name) { return ResourceKey.create(Registries.DIMENSION, id(name)); }
    public static boolean isHorse(Entity entity) { return entity instanceof AbstractHorse; }
    public static CompoundTag previewSource(Entity entity) { return entity.saveWithoutId(new CompoundTag()); }
    public static boolean mount(Entity passenger, Entity vehicle, boolean force) { return passenger.startRiding(vehicle, force); }
    public static Vec3 riderPosition(Entity vehicle, Entity passenger) {
        return vehicle.position().add(0, vehicle.getPassengersRidingOffset() + passenger.getMyRidingOffset(), 0);
    }
    public static UUID owner(Entity entity) {
        if (entity instanceof TamableAnimal pet) return pet.isTame() ? pet.getOwnerUUID() : null;
        if (entity instanceof AbstractHorse horse) return horse.isTamed() ? horse.getOwnerUUID() : null;
        return entity instanceof OwnableEntity ownable ? ownable.getOwnerUUID() : null;
    }
    public static boolean canRide(Entity entity, ServerPlayer player) {
        return entity instanceof AbstractHorse horse && horse.isTamed() && !horse.isBaby()
                && horse.isSaddled() && player.getUUID().equals(owner(horse));
    }
    public static double speed(LivingEntity entity) { return entity.getAttributeValue(Attributes.MOVEMENT_SPEED); }
    public static double jump(Entity entity) { return entity instanceof AbstractHorse horse ? horse.getCustomJump() : 0; }
    public static int minY(ServerLevel level) { return level.getMinBuildHeight(); }
    public static int maxY(ServerLevel level) { return level.getMaxBuildHeight(); }
    public static long chunkKey(ChunkPos pos) { return pos.toLong(); }
    public static Entity transfer(Entity entity, ServerLevel level, Vec3 pos) {
        return LegacyTeleportTarget.transfer(entity, level, pos);
    }
    public static ChunkLease retain(ServerLevel level, int x, int z, UUID operation) {
        var pos = new ChunkPos(x, z);
        level.getChunkSource().addRegionTicket(RECALL, pos, 2, operation);
        return () -> level.getChunkSource().removeRegionTicket(RECALL, pos, 2, operation);
    }
    public static void whistle(ServerPlayer player) {
        ((ServerLevel) player.level()).playSound(null, player.blockPosition(), WHISTLE, SoundSource.PLAYERS, 0.35F, 1.0F);
    }
    public static UUID uuid(CompoundTag tag) { return tag.hasUUID("UUID") ? tag.getUUID("UUID") : null; }
    public static String string(CompoundTag tag, String key) { return tag.getString(key); }
    public static ListTag list(CompoundTag tag, String key) { return tag.getList(key, 10); }
    public static CompoundTag chunkTag(ChunkPos pos) {
        var tag = NbtUtils.addCurrentDataVersion(new CompoundTag());
        tag.put("Entities", new ListTag());
        tag.putIntArray("Position", new int[]{pos.x, pos.z});
        return tag;
    }
    public static ChunkPos storedChunk(CompoundTag tag) {
        int[] position = tag.getIntArray("Position");
        return new ChunkPos(position[0], position[1]);
    }
}
