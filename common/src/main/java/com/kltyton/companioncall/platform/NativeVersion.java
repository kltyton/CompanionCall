package com.kltyton.companioncall.platform;

import java.nio.file.Path;
import java.util.UUID;
import com.kltyton.companioncall.api.OwnerChangeResult;
import com.kltyton.companioncall.api.TravelMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.phys.Vec3;

public final class NativeVersion {
    private NativeVersion() {}
    public static Path configDirectory() { return Path.of("config"); }
    public static String dimension(Level level) { return VersionCalls.dimension(level); }
    public static ResourceKey<Level> levelKey(String name) { return VersionCalls.levelKey(name); }
    public static UUID owner(Entity entity) { return VersionCalls.owner(entity); }
    public static OwnerChangeResult setOwner(Entity entity, ServerPlayer owner) { return VersionCalls.setOwner(entity, owner); }
    public static boolean canManage(CommandSourceStack source) { return VersionCalls.canManage(source); }
    public static CompoundTag previewSource(Entity entity) { return VersionCalls.previewSource(entity); }
    public static boolean isHorse(Entity entity) { return VersionCalls.isHorse(entity); }
    public static boolean canRide(Entity entity, ServerPlayer owner) { return VersionCalls.canRide(entity, owner); }
    public static boolean mount(Entity passenger, Entity vehicle, boolean force) { return VersionCalls.mount(passenger, vehicle, force); }
    public static Vec3 riderPosition(Entity vehicle, Entity passenger) { return VersionCalls.riderPosition(vehicle, passenger); }
    public static void synchronizeMovement(Entity entity) { VersionCalls.synchronizeMovement(entity); }
    public static void setPathMode(Node node, TravelMode mode) { VersionCalls.setPathMode(node, mode); }
    public static Vec3 knownMovement(ServerPlayer player, Vec3 measured) { return VersionCalls.knownMovement(player, measured); }
    public static double nextAirVelocity(LivingEntity entity, double velocity) { return VersionCalls.nextAirVelocity(entity, velocity); }
    public static void closeViewers(Entity entity) {
        var level = (ServerLevel) entity.level();
        for (var player : level.getServer().getPlayerList().getPlayers()) {
            if (menuEntity(player.containerMenu) == entity) player.closeContainer();
        }
    }
    public static Entity menuEntity(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        if (menu instanceof com.kltyton.companioncall.storage.transfer.PetMenuAccess petMenu) return petMenu.companionCall$pet();
        for (var slot : menu.slots) if (slot.container instanceof Entity entity) return entity;
        return null;
    }
    public static double speed(Entity entity) { return entity instanceof LivingEntity living ? VersionCalls.speed(living) : 0; }
    public static double jump(Entity entity) { return VersionCalls.jump(entity); }
    public static int minY(ServerLevel level) { return VersionCalls.minY(level); }
    public static int maxY(ServerLevel level) { return VersionCalls.maxY(level); }
    public static long chunkKey(ChunkPos pos) { return VersionCalls.chunkKey(pos); }
    public static Entity transfer(Entity entity, ServerLevel level, Vec3 pos) { return VersionCalls.transfer(entity, level, pos); }
    public static ChunkLease retain(ServerLevel level, int x, int z, UUID operation) { return VersionCalls.retain(level, x, z, operation); }
    public static void whistle(ServerPlayer player) { VersionCalls.whistle(player); }
    public static UUID uuid(CompoundTag tag) { return VersionCalls.uuid(tag); }
    public static String string(CompoundTag tag, String key) { return VersionCalls.string(tag, key); }
    public static ListTag list(CompoundTag tag, String key) { return VersionCalls.list(tag, key); }
    public static CompoundTag chunkTag(ChunkPos pos) { return VersionCalls.chunkTag(pos); }
    public static ChunkPos storedChunk(CompoundTag tag) { return VersionCalls.storedChunk(tag); }
}
