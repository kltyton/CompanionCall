package com.kltyton.companioncall.client.map;

import com.google.gson.JsonParseException;
import com.kltyton.companioncall.api.client.CompanionMapApi.Companion;
import com.kltyton.companioncall.network.map.CompanionMapProtocol;
import com.kltyton.companioncall.network.payload.ClientRequest;
import com.kltyton.companioncall.platform.NativeVersion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class CompanionMapState {
    private static final long SNAPSHOT_NANOS = 2_500_000_000L;
    private static ClientPacketListener supportedConnection;
    private static ClientLevel listedLevel;
    private static UUID query;
    private static long nextPoll;
    private static long lastReply;
    private static boolean paused;
    private static long pausedAt;
    private static long pausedNanos;
    private static List<Companion> snapshot = List.of();

    private CompanionMapState() {}

    public static void advertise(int version) {
        var connection = version == CompanionMapProtocol.VERSION ? Minecraft.getInstance().getConnection() : null;
        if (connection != supportedConnection) clearList();
        supportedConnection = connection;
    }

    private static void clearList() {
        listedLevel = null;
        query = null;
        nextPoll = 0;
        lastReply = 0;
        paused = false;
        pausedAt = 0;
        pausedNanos = 0;
        snapshot = List.of();
    }

    public static void tick() {
        if (listedLevel == null) return;
        var client = Minecraft.getInstance();
        if (client.level != listedLevel || client.getConnection() != supportedConnection) {
            clearList();
        } else {
            snapshotTime(System.nanoTime());
        }
    }

    public static boolean isSupported(ClientLevel level) {
        var client = Minecraft.getInstance();
        return level != null && client.isSameThread() && level == client.level && client.player != null
                && supportedConnection != null && client.getConnection() == supportedConnection;
    }

    public static List<Companion> companions(ClientLevel level) {
        if (!isSupported(level) || !canIssueOrders()) return List.of();
        long now = System.nanoTime();
        if (listedLevel != level) {
            clearList();
            listedLevel = level;
        }
        long snapshotNow = snapshotTime(now);
        boolean expired = snapshotNow - lastReply >= SNAPSHOT_NANOS;
        if (query == null || now - nextPoll >= 0) {
            if (query == null || (expired && now - nextPoll > SNAPSHOT_NANOS)) {
                query = UUID.randomUUID();
                snapshot = List.of();
            }
            nextPoll = now + CompanionMapProtocol.LIST_INTERVAL_NANOS;
            new ClientRequest(CompanionMapProtocol.LIST, query.toString()).sendToServer();
        }
        return expired ? List.of() : snapshot;
    }

    public static boolean moveTo(ClientLevel level, Collection<UUID> companions, BlockPos goal) {
        if (!isSupported(level) || !canIssueOrders() || companions == null || goal == null || companions.isEmpty()) return false;
        var ids = new LinkedHashSet<UUID>();
        for (UUID id : companions) {
            if (id == null) return false;
            ids.add(id);
            if (ids.size() > CompanionMapProtocol.MAX_BATCH) return false;
        }
        var move = new CompanionMapProtocol.Move(NativeVersion.dimension(level),
                goal.getX(), goal.getY(), goal.getZ(), List.copyOf(ids));
        String payload = CompanionMapProtocol.encodeMove(move);
        if (payload.length() > CompanionMapProtocol.MAX_MOVE_CHARS) return false;
        new ClientRequest(CompanionMapProtocol.MOVE, payload).sendToServer();
        return true;
    }

    public static void receive(String json) {
        if (query == null || !isSupported(listedLevel)) return;
        CompanionMapProtocol.Snapshot updated;
        try {
            updated = CompanionMapProtocol.decodeSnapshot(json);
        } catch (JsonParseException | IllegalArgumentException error) {
            snapshot = List.of();
            return;
        }
        if (updated == null || !query.equals(updated.query())
                || !NativeVersion.dimension(listedLevel).equals(updated.dimension()) || updated.companions() == null) return;
        var result = new ArrayList<Companion>(updated.companions().size());
        for (var companion : updated.companions()) {
            if (companion == null || companion.id() == null || companion.type() == null || companion.name() == null
                    || !Double.isFinite(companion.x()) || !Double.isFinite(companion.y()) || !Double.isFinite(companion.z())) continue;
            result.add(new Companion(companion.id(), new Vec3(companion.x(), companion.y(), companion.z()),
                    companion.type(), companion.name(), companion.entityId()));
        }
        snapshot = List.copyOf(result);
        lastReply = snapshotTime(System.nanoTime());
    }

    /** The freshness clock excludes time during an integrated server pause. */
    private static long snapshotTime(long now) {
        var client = Minecraft.getInstance();
        boolean pause = client.hasSingleplayerServer() && client.isPaused();
        if (pause != paused) {
            if (pause) pausedAt = now;
            else pausedNanos += now - pausedAt;
            paused = pause;
        }
        return (paused ? pausedAt : now) - pausedNanos;
    }

    private static boolean canIssueOrders() {
        var player = Minecraft.getInstance().player;
        return player != null && player.isAlive() && !player.isSpectator();
    }
}
