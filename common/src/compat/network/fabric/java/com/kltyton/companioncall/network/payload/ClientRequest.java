package com.kltyton.companioncall.network.payload;

import com.kltyton.companioncall.platform.VersionCalls;
import com.kltyton.companioncall.network.map.CompanionMapProtocol;
import java.util.function.Consumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record ClientRequest(String action, String pet) implements CustomPacketPayload {
    public static final Type<ClientRequest> TYPE = new Type<>(VersionCalls.id("companion_call:request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientRequest> CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUtf(value.action, 16);
                buffer.writeUtf(value.pet, CompanionMapProtocol.requestLimit(value.action));
            },
            buffer -> {
                String action = buffer.readUtf(16);
                return new ClientRequest(action, buffer.readUtf(CompanionMapProtocol.requestLimit(action)));
            });
    private static Consumer<ClientRequest> sender;

    public static void installSender(Consumer<ClientRequest> transport) { sender = transport; }
    public void sendToServer() {
        if (sender == null) throw new IllegalStateException("Companion client transport is not initialized");
        sender.accept(this);
    }
    @Override public Type<ClientRequest> type() { return TYPE; }
}
