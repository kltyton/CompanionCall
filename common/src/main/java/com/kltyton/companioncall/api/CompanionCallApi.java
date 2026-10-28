package com.kltyton.companioncall.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import com.kltyton.companioncall.platform.NativeVersion;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.entity.ownership.BoundEntityAdapter;

/** Shared registration point used by standalone and embedded installations. */
public final class CompanionCallApi {
    private record Registration(String id, int priority, PetAdapter adapter) {}
    private static volatile List<Registration> adapters = List.of();

    private CompanionCallApi() {}

    /** Register during common initialization. Higher priorities are considered first. */
    public static synchronized void registerAdapter(String id, int priority, PetAdapter adapter) {
        if (!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid adapter id: " + id);
        }
        Objects.requireNonNull(adapter, "adapter");
        if (adapters.stream().anyMatch(entry -> entry.id().equals(id))) {
            throw new IllegalStateException("Pet adapter already registered: " + id);
        }
        var updated = new ArrayList<>(adapters);
        updated.add(new Registration(id, priority, adapter));
        updated.sort(Comparator.comparingInt(Registration::priority).reversed()
                .thenComparing(Registration::id));
        adapters = List.copyOf(updated);
    }

    public static PetAdapter adapter(Entity entity) {
        for (var registration : adapters) {
            if (registration.adapter().matches(entity)) {
                if (registration.adapter().owner(entity) == null && BoundEntityAdapter.INSTANCE.owner(entity) != null) {
                    return BoundEntityAdapter.INSTANCE;
                }
                return registration.adapter();
            }
        }
        return BoundEntityAdapter.INSTANCE;
    }

    /** Closes native inventory viewers for this pet before its transfer snapshot is saved. */
    public static void closeViewers(Entity entity) {
        NativeVersion.closeViewers(entity);
    }

    public static boolean tryBoard(Entity entity, ServerPlayer owner) {
        return entity.level() instanceof ServerLevel level
                && CompanionCall.server(level.getServer()).recalls.tryBoard(entity, owner);
    }
}
