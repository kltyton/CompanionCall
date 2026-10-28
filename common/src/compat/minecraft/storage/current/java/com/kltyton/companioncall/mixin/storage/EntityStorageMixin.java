package com.kltyton.companioncall.mixin.storage;

import com.kltyton.companioncall.platform.NativeVersion;
import com.kltyton.companioncall.storage.transfer.EntityStorageAccess;
import com.kltyton.companioncall.storage.transfer.StorageHooks;
import com.kltyton.companioncall.storage.transfer.StorageWriteTracker;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.EntityStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import net.minecraft.world.level.entity.ChunkEntities;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Captures the exact native write future and filters transfer records before entity construction. */
@Mixin(EntityStorage.class)
public abstract class EntityStorageMixin implements EntityStorageAccess {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private SimpleRegionStorage simpleRegionStorage;
    @Shadow @Final private LongSet emptyChunks;
    @Shadow public abstract CompletableFuture<ChunkEntities<Entity>> loadEntities(ChunkPos pos);
    @Unique private final StorageWriteTracker companionCall$writes = new StorageWriteTracker();

    @Override public CompletableFuture<Void> companionCall$nextWrite(ChunkPos pos) {
        emptyChunks.remove(NativeVersion.chunkKey(pos));
        return companionCall$writes.next(pos);
    }
    @Override public void companionCall$cancelWait(ChunkPos pos, CompletableFuture<Void> request) {
        companionCall$writes.cancel(pos, request);
    }
    @Override public CompletableFuture<ChunkEntities<Entity>> companionCall$read(ChunkPos pos) {
        emptyChunks.remove(NativeVersion.chunkKey(pos));
        return loadEntities(pos);
    }

    @WrapOperation(method = "storeEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/SimpleRegionStorage;write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> companionCall$write(SimpleRegionStorage storage, ChunkPos pos, CompoundTag tag,
                                                       Operation<CompletableFuture<Void>> original) {
        var decorated = StorageHooks.write(level, pos, tag);
        if (decorated != null && !NativeVersion.list(decorated, "Entities").isEmpty()) emptyChunks.remove(NativeVersion.chunkKey(pos));
        var result = original.call(storage, pos, decorated);
        companionCall$writes.submitted(pos, result, () -> simpleRegionStorage.synchronize(true));
        return result;
    }
    
    @WrapOperation(method = "storeEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/SimpleRegionStorage;write(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Void> companionCall$emptyWrite(SimpleRegionStorage storage, ChunkPos pos,
            Supplier<CompoundTag> supplier, Operation<CompletableFuture<Void>> original) {
        var decorated = StorageHooks.write(level, pos, supplier.get());
        if (decorated != null && !NativeVersion.list(decorated, "Entities").isEmpty()) emptyChunks.remove(NativeVersion.chunkKey(pos));
        Supplier<CompoundTag> snapshot = () -> decorated;
        var result = original.call(storage, pos, snapshot);
        companionCall$writes.submitted(pos, result, () -> simpleRegionStorage.synchronize(true));
        return result;
    }
    @ModifyExpressionValue(method = "*", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/SimpleRegionStorage;upgradeChunkTag(Lnet/minecraft/nbt/CompoundTag;I)Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag companionCall$beforeDeserialize(CompoundTag tag) { return StorageHooks.read(level, tag); }
}
