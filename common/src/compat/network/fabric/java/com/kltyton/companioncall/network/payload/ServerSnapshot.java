package com.kltyton.companioncall.network.payload;

import com.kltyton.companioncall.platform.VersionCalls;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Bounded ordered snapshot fragments, shared by native Fabric networking generations. */
public record ServerSnapshot(String json) {
    private static final int FRAGMENT = 16384;
    private static final int MAX_BYTES = 64 * 1024 * 1024;
    private static Consumer<String> receiver;
    private static UUID receiving;
    private static ByteArrayOutputStream assembled;
    private static int next;
    private static int expectedCount;
    private static int expectedBytes;

    public record Part(UUID transfer, int index, int count, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<Part> TYPE = new Type<>(VersionCalls.id("companion_call:snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Part> CODEC = StreamCodec.of(
                (buffer, value) -> {
                    buffer.writeUUID(value.transfer); buffer.writeVarInt(value.index);
                    buffer.writeVarInt(value.count); buffer.writeVarInt(value.total); buffer.writeByteArray(value.data);
                }, buffer -> new Part(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(),
                        buffer.readVarInt(), buffer.readByteArray(FRAGMENT)));
        public Part {
            if (total <= 0 || total > MAX_BYTES || count != (total + FRAGMENT - 1) / FRAGMENT
                    || index < 0 || index >= count || data.length != Math.min(FRAGMENT, total - index * FRAGMENT)) {
                throw new IllegalArgumentException("Invalid companion state fragment");
            }
        }
        @Override public Type<Part> type() { return TYPE; }
    }

    public void sendTo(ServerPlayer player) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Companion state exceeds transfer quota");
        UUID transfer = UUID.randomUUID();
        int count = (bytes.length + FRAGMENT - 1) / FRAGMENT;
        for (int index = 0; index < count; index++) {
            int start = index * FRAGMENT;
            ServerPlayNetworking.send(player, new Part(transfer, index, count, bytes.length,
                    Arrays.copyOfRange(bytes, start, Math.min(bytes.length, start + FRAGMENT))));
        }
    }

    public static void installReceiver(Consumer<String> transport) { receiver = transport; }
    public static void clearAssembly() { receiving = null; assembled = null; next = 0; }
    public static void accept(Part part) {
        if (part.index == 0) {
            clearAssembly();
            receiving = part.transfer;
            expectedCount = part.count;
            expectedBytes = part.total;
            assembled = new ByteArrayOutputStream(FRAGMENT);
        }
        if (!part.transfer.equals(receiving) || part.index != next || part.count != expectedCount
                || part.total != expectedBytes) {
            clearAssembly();
            throw new IllegalArgumentException("Out of order companion state fragments");
        }
        assembled.writeBytes(part.data);
        next++;
        if (next == expectedCount) {
            String json = assembled.toString(StandardCharsets.UTF_8);
            clearAssembly();
            if (receiver == null) throw new IllegalStateException("Companion client is not initialized");
            receiver.accept(json);
        }
    }
}
