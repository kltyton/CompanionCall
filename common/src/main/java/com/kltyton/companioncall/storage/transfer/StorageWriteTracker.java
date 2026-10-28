package com.kltyton.companioncall.storage.transfer;

import com.kltyton.companioncall.platform.NativeVersion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;

/** Couples a requested chunk snapshot to its own write result and storage flush. */
public final class StorageWriteTracker {
    private final Map<Long, List<CompletableFuture<Void>>> waiting = new HashMap<>();

    public CompletableFuture<Void> next(ChunkPos pos) {
        var future = new CompletableFuture<Void>();
        waiting.computeIfAbsent(NativeVersion.chunkKey(pos), ignored -> new ArrayList<>()).add(future);
        return future;
    }

    public void cancel(ChunkPos pos, CompletableFuture<Void> future) {
        var requests = waiting.get(NativeVersion.chunkKey(pos));
        if (requests != null) {
            requests.remove(future);
            if (requests.isEmpty()) waiting.remove(NativeVersion.chunkKey(pos));
        }
    }

    public void submitted(ChunkPos pos, CompletableFuture<Void> write,
                          Supplier<CompletableFuture<Void>> flush) {
        var requests = waiting.remove(NativeVersion.chunkKey(pos));
        if (requests == null) return;
        write.thenCompose(ignored -> flush.get()).whenComplete((ignored, failure) -> {
            for (var request : requests) {
                if (failure == null) request.complete(null);
                else request.completeExceptionally(failure);
            }
        });
    }
}
