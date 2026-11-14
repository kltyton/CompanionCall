package com.kltyton.companioncall.api.client;

import com.kltyton.companioncall.client.map.CompanionMapState;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Client-only bridge for optional world maps. Call from the Minecraft client thread. */
public final class CompanionMapApi {
    /** Server-authorized snapshot. Verify the UUID when resolving the transient entity ID for rendering. */
    public record Companion(UUID id, Vec3 position, String entityType, String displayName, int entityId) {}

    private CompanionMapApi() {}

    /** True once this connection advertises a compatible map service. */
    public static boolean isSupported(ClientLevel level) { return CompanionMapState.isSupported(level); }

    /** Poll while the map is open. Returns the latest immutable snapshot for this client level. */
    public static List<Companion> companions(ClientLevel level) { return CompanionMapState.companions(level); }

    /**
     * Submits up to 32 unique companions to a block in the given current level.
     * True means the request was sent. The server checks each companion independently.
     */
    public static boolean moveTo(ClientLevel level, Collection<UUID> companions, BlockPos goal) {
        return CompanionMapState.moveTo(level, companions, goal);
    }
}
