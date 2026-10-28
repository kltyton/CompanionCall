package com.kltyton.companioncall.loader.neoforge;

import com.kltyton.companioncall.client.input.ClientAccess;
import com.kltyton.companioncall.client.state.CompanionClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

@EventBusSubscriber(modid = "companion_call", value = Dist.CLIENT)
public final class NeoForgeKeyRegistration {
    private NeoForgeKeyRegistration() {}
    @SubscribeEvent public static void register(RegisterKeyMappingsEvent event) {
        CompanionClient.initialize();
        event.register(ClientAccess.KEY);
        event.register(ClientAccess.ADD_RIDDEN);
    }
}
