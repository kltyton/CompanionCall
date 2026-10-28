package com.kltyton.companioncall.loader.forge;

import com.kltyton.companioncall.client.input.ClientAccess;
import com.kltyton.companioncall.client.state.CompanionClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "companion_call", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ForgeKeyRegistration {
    private ForgeKeyRegistration() {}
    @SubscribeEvent public static void register(RegisterKeyMappingsEvent event) {
        CompanionClient.initialize();
        event.register(ClientAccess.KEY);
        event.register(ClientAccess.ADD_RIDDEN);
    }
}
