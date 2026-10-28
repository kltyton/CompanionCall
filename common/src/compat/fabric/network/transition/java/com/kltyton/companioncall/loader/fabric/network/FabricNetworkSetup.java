package com.kltyton.companioncall.loader.fabric.network;

import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.network.payload.ClientRequest;
import com.kltyton.companioncall.network.payload.ServerSnapshot;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class FabricNetworkSetup implements ModInitializer {
    @Override public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(ClientRequest.TYPE, ClientRequest.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerSnapshot.Part.TYPE, ServerSnapshot.Part.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ClientRequest.TYPE, (packet, context) ->
                CompanionCall.server(context.server()).request(context.player(), packet.action(), packet.pet()));
    }
}
