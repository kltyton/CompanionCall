package com.kltyton.companioncall.storage.transfer;

import com.kltyton.companioncall.mixin.access.EntityManagerAccess;
import com.kltyton.companioncall.mixin.access.ServerLevelAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Requests a loaded chunk snapshot without blocking the tick while the native load is pending. */
public final class NativeChunkBarrier {
    private record Pending(ServerLevel level, ChunkPos pos, long expires, CompletableFuture<Void> result) {}
    private final List<Pending> pending = new ArrayList<>();

    public CompletableFuture<Void> save(ServerLevel level, ChunkPos pos, long now) {
        var result = new CompletableFuture<Void>();
        pending.add(new Pending(level, pos, now + 400, result));
        return result;
    }

    public void tick(long now) {
        for (var request : new ArrayList<>(pending)) {
            if (now >= request.expires()) {
                request.result().completeExceptionally(new IllegalStateException("Timed out waiting for native entity chunk load"));
                pending.remove(request);
                continue;
            }
            var manager = (EntityManagerAccess) ((ServerLevelAccess) request.level()).companionCall$entities();
            var storage = (EntityStorageAccess) manager.companionCall$storage();
            manager.companionCall$queueLoad(com.kltyton.companioncall.platform.NativeVersion.chunkKey(request.pos()));
            var write = storage.companionCall$nextWrite(request.pos());
            // The visitor preserves live entities; unloading is a separate native operation.
            if (!manager.companionCall$save(com.kltyton.companioncall.platform.NativeVersion.chunkKey(request.pos()), entity -> {})) {
                storage.companionCall$cancelWait(request.pos(), write);
                continue;
            }
            pending.remove(request);
            write.whenComplete((ignored, error) -> {
                if (error == null) request.result().complete(null);
                else request.result().completeExceptionally(error);
            });
        }
    }
}
