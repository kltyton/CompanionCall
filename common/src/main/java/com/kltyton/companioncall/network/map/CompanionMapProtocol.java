package com.kltyton.companioncall.network.map;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.util.List;
import java.util.UUID;

/** Optional map messages on the existing CompanionCall play channels. */
public final class CompanionMapProtocol {
    public static final int VERSION = 1;
    public static final String LIST = "map_list_v1";
    public static final String MOVE = "map_move_v1";
    public static final int MAX_BATCH = 32;
    public static final int MAX_MOVE_CHARS = 3072;
    public static final long LIST_INTERVAL_NANOS = 500_000_000L;
    private static final String LIST_PREFIX = "{\"mapNavigation\":";
    private static final Gson GSON = new Gson();

    public record Move(String dimension, Integer x, Integer y, Integer z, List<UUID> ids) {}
    public record Companion(UUID id, int entityId, String type, String name, double x, double y, double z) {}
    public record Snapshot(UUID query, String dimension, List<Companion> companions) {}
    private record Envelope(Snapshot mapNavigation) {}

    private CompanionMapProtocol() {}

    public static int requestLimit(String action) { return MOVE.equals(action) ? MAX_MOVE_CHARS : 36; }

    public static String encodeMove(Move move) { return GSON.toJson(move); }

    public static Move decodeMove(String json) {
        try {
            return GSON.fromJson(json, Move.class);
        } catch (JsonParseException error) {
            return null;
        }
    }

    public static String encodeSnapshot(Snapshot snapshot) { return GSON.toJson(new Envelope(snapshot)); }

    public static boolean isSnapshot(String json) { return json.startsWith(LIST_PREFIX); }

    public static Snapshot decodeSnapshot(String json) {
        var envelope = GSON.fromJson(json, Envelope.class);
        return envelope == null ? null : envelope.mapNavigation();
    }
}
