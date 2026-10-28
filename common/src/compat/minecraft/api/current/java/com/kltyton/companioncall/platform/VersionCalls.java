package com.kltyton.companioncall.platform;

import java.util.UUID;
import com.kltyton.companioncall.api.OwnerChangeResult;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;
import com.kltyton.companioncall.CompanionCall;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.portal.TeleportTransition;

public final class VersionCalls {
    public static void setPathMode(net.minecraft.world.level.pathfinder.Node node, com.kltyton.companioncall.api.TravelMode mode) {
        node.type = switch (mode) {
            case LAND -> net.minecraft.world.level.pathfinder.PathType.WALKABLE;
            case AIR -> net.minecraft.world.level.pathfinder.PathType.OPEN;
            case WATER -> net.minecraft.world.level.pathfinder.PathType.WATER;
        };
    }
    public static void synchronizeMovement(Entity entity) { entity.needsSync = true; }
    public static Vec3 knownMovement(ServerPlayer player, Vec3 measured) { return player.getKnownMovement(); }
    public static double nextAirVelocity(LivingEntity entity, double velocity) {
        var levitation = entity.getEffect(net.minecraft.world.effect.MobEffects.LEVITATION);
        if (levitation != null) velocity += (.05 * (levitation.getAmplifier() + 1) - velocity) * .2;
        else {
            double gravity = entity.getGravity();
            if (velocity <= 0 && entity.hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING)) gravity = Math.min(gravity, .01);
            velocity -= gravity;
        }
        return velocity * .98;
    }
    private static final TicketType RECALL = new TicketType(0, 2 | 4 | 8);
    private record LeaseKey(ServerLevel level, int x, int z) {}
    private static final java.util.Map<LeaseKey, Integer> LEASES = new java.util.HashMap<>();
    private static final SoundEvent WHISTLE = SoundEvent.createVariableRangeEvent(id("companion_call:whistle"));
    private VersionCalls() {}
    public static boolean canManage(CommandSourceStack source) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source);
    }
    public static OwnerChangeResult setOwner(Entity entity, ServerPlayer owner) {
        if (entity instanceof TamableAnimal pet) {
            if (pet.isTame()) pet.setOwner(owner);
            else pet.tame(owner);
        } else if (entity instanceof AbstractHorse horse) {
            if (horse.isTamed()) horse.setOwner(owner);
            else horse.tameWithName(owner);
        } else return OwnerChangeResult.UNSUPPORTED;
        return owner.getUUID().equals(owner(entity)) ? OwnerChangeResult.SUCCESS : OwnerChangeResult.REJECTED;
    }
    public static Identifier id(String name) { return Identifier.parse(name); }
    public static String dimension(Level level) { return level.dimension().identifier().toString(); }
    public static ResourceKey<Level> levelKey(String name) { return ResourceKey.create(Registries.DIMENSION, id(name)); }
    public static boolean isHorse(Entity entity) { return entity instanceof AbstractHorse; }
    public static CompoundTag previewSource(Entity entity) {
        try (var reporter = new ProblemReporter.ScopedCollector(entity.problemPath(), CompanionCall.LOGGER)) {
            var output = TagValueOutput.createWithContext(reporter, entity.registryAccess());
            entity.saveWithoutId(output);
            return output.buildResult();
        }
    }
    public static boolean mount(Entity passenger, Entity vehicle, boolean force) { return passenger.startRiding(vehicle, force, true); }
    public static Vec3 riderPosition(Entity vehicle, Entity passenger) {
        return vehicle.getPassengerRidingPosition(passenger).subtract(passenger.getVehicleAttachmentPoint(vehicle));
    }
    public static UUID owner(Entity entity) {
        if (entity instanceof TamableAnimal pet) {
            var reference = pet.getOwnerReference();
            return pet.isTame() && reference != null ? reference.getUUID() : null;
        }
        if (entity instanceof AbstractHorse horse) {
            var reference = horse.getOwnerReference();
            return horse.isTamed() && reference != null ? reference.getUUID() : null;
        }
        if (entity instanceof OwnableEntity ownable) {
            var reference = ownable.getOwnerReference();
            return reference == null ? null : reference.getUUID();
        }
        return null;
    }
    public static boolean canRide(Entity entity, ServerPlayer player) {
        return entity instanceof AbstractHorse horse && horse.isTamed() && !horse.isBaby()
                && horse.isSaddled() && player.getUUID().equals(owner(horse));
    }
    public static double speed(LivingEntity entity) { return entity.getAttributeValue(Attributes.MOVEMENT_SPEED); }
    public static double jump(Entity entity) { return entity instanceof AbstractHorse horse ? horse.getAttributeValue(Attributes.JUMP_STRENGTH) : 0; }
    public static int minY(ServerLevel level) { return level.getMinY(); }
    public static int maxY(ServerLevel level) { return level.getMaxY(); }
    public static long chunkKey(ChunkPos pos) { return pos.pack(); }
    public static Entity transfer(Entity entity, ServerLevel level, Vec3 pos) {
        return entity.teleport(new TeleportTransition(level, pos, Vec3.ZERO, entity.getYRot(), entity.getXRot(), TeleportTransition.DO_NOTHING));
    }
    public static ChunkLease retain(ServerLevel level, int x, int z, UUID operation) {
        var pos = new ChunkPos(x, z);
        var key = new LeaseKey(level, x, z);
        int count = LEASES.getOrDefault(key, 0);
        LEASES.put(key, count + 1);
        if (count == 0) level.getChunkSource().addTicketWithRadius(RECALL, pos, 2);
        return () -> {
            int remaining = LEASES.getOrDefault(key, 1) - 1;
            if (remaining == 0) {
                LEASES.remove(key);
                level.getChunkSource().removeTicketWithRadius(RECALL, pos, 2);
            } else LEASES.put(key, remaining);
        };
    }
    public static void whistle(ServerPlayer player) {
        ((ServerLevel) player.level()).playSound(null, player.blockPosition(), WHISTLE, SoundSource.PLAYERS, 0.35F, 1.0F);
    }
    public static UUID uuid(CompoundTag tag) { return tag.read("UUID", UUIDUtil.CODEC).orElse(null); }
    public static String string(CompoundTag tag, String key) { return tag.getStringOr(key, ""); }
    public static ListTag list(CompoundTag tag, String key) { return tag.getListOrEmpty(key); }
    public static CompoundTag chunkTag(ChunkPos pos) {
        var tag = NbtUtils.addCurrentDataVersion(new CompoundTag());
        tag.put("Entities", new ListTag());
        tag.store("Position", ChunkPos.CODEC, pos);
        return tag;
    }
    public static ChunkPos storedChunk(CompoundTag tag) {
        return tag.read("Position", ChunkPos.CODEC).orElseThrow();
    }
}
