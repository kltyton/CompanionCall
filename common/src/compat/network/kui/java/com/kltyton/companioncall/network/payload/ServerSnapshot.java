package com.kltyton.companioncall.network.payload;

import io.github.kltyton.kltytonui.network.api.INetworkContext;
import io.github.kltyton.kltytonui.network.api.INetworkPacket;
import io.github.kltyton.kltytonui.network.api.NetworkPacket;
import io.github.kltyton.kltytonui.network.api.Side;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

@NetworkPacket(modId = "companion_call", id = "snapshot", side = Side.CLIENT, chunkThreshold = 16384)
public record ServerSnapshot(byte[] data) implements INetworkPacket<ServerSnapshot> {
    private static Consumer<String> receiver;

    public ServerSnapshot(String json) { this(json.getBytes(StandardCharsets.UTF_8)); }

    public static void installReceiver(Consumer<String> listener) { receiver = listener; }

    @Override public void handle(INetworkContext context) {
        if (!context.isClientSide()) return;
        context.enqueueWork(() -> {
            if (receiver == null) throw new IllegalStateException("Companion client was not initialized");
            receiver.accept(new String(data, StandardCharsets.UTF_8));
        });
    }
}
