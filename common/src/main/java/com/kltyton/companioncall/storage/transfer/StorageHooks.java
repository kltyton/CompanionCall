package com.kltyton.companioncall.storage.transfer;

import com.kltyton.companioncall.CompanionCall;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;

/** Storage-boundary hooks shared by all four native serialization generations. */
public final class StorageHooks {
    public static final String NONCE = "CompanionCallTransfer";
    public static final String ROLE = "CompanionCallTransferRole";
    private static final ThreadLocal<Integer> INTERNAL = ThreadLocal.withInitial(() -> 0);
    private StorageHooks() {}

    public static boolean internal() { return INTERNAL.get() > 0; }

    public static <T> T internally(Supplier<T> operation) {
        INTERNAL.set(INTERNAL.get() + 1);
        try { return operation.get(); }
        finally {
            int depth = INTERNAL.get() - 1;
            if (depth == 0) INTERNAL.remove(); else INTERNAL.set(depth);
        }
    }

    public static boolean quarantined(Entity entity) {
        return !internal() && !((TransferMark) entity).companionCall$loading() && entity.level() instanceof ServerLevel level
                && CompanionCall.server(level.getServer()).transfers.locked(entity.getUUID());
    }

    public static CompoundTag write(ServerLevel level, ChunkPos pos, CompoundTag tag) {
        return CompanionCall.server(level.getServer()).transfers.decorate(level, pos, tag);
    }

    public static CompoundTag read(ServerLevel level, CompoundTag tag) {
        return CompanionCall.server(level.getServer()).transfers.filterLoad(level, tag);
    }

    public static void restored(Entity target) {
        if (!(target.level() instanceof ServerLevel level)) return;
        var runtime = CompanionCall.server(level.getServer());
        var operation = runtime.transfers.operationFor(target.getUUID());
        if (operation != null) ((TransferMark) target).companionCall$mark(operation.toString(), "destination");
    }
}
