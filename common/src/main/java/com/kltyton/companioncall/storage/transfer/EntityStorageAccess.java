package com.kltyton.companioncall.storage.transfer;

import java.util.concurrent.CompletableFuture;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.ChunkEntities;

public interface EntityStorageAccess {
    CompletableFuture<Void> companionCall$nextWrite(ChunkPos pos);
    void companionCall$cancelWait(ChunkPos pos, CompletableFuture<Void> request);
    CompletableFuture<ChunkEntities<Entity>> companionCall$read(ChunkPos pos);
}
