package com.kltyton.companioncall.client.network;

import com.kltyton.companioncall.network.payload.ClientRequest;
import com.kltyton.companioncall.network.payload.ServerSnapshot;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class FabricClientNetworkSetup implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientRequest.installSender(ClientPlayNetworking::send);
        ClientPlayNetworking.registerGlobalReceiver(ServerSnapshot.Part.TYPE,
                (part, context) -> ServerSnapshot.accept(part));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ServerSnapshot.clearAssembly());
    }
}
