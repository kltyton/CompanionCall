package com.kltyton.companioncall.network.payload;

import com.kltyton.companioncall.CompanionCall;
import io.github.kltyton.kltytonui.network.api.INetworkContext;
import io.github.kltyton.kltytonui.network.api.INetworkPacket;
import io.github.kltyton.kltytonui.network.api.NetworkPacket;
import io.github.kltyton.kltytonui.network.api.Side;

@NetworkPacket(modId = "companion_call", id = "request", side = Side.SERVER)
public record ClientRequest(String action, String pet) implements INetworkPacket<ClientRequest> {
    @Override public void handle(INetworkContext context) {
        if (!context.isServerSide() || context.sender() == null) return;
        if (action == null || action.length() > 16 || pet == null || pet.length() > 36) return;
        var sender = context.sender();
        context.enqueueWork(() -> CompanionCall.server(((net.minecraft.server.level.ServerLevel) sender.level()).getServer())
                .request(sender, action, pet));
    }
}
