package com.kltyton.companioncall.client.compat;

import com.kltyton.companioncall.client.compat.geckolib.GeckoRecallOutlines;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = "companion_call", value = Dist.CLIENT)
public final class ClientRenderIntegrations {
    private ClientRenderIntegrations() {}

    @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
        if (ModList.get().isLoaded("geckolib")) GeckoRecallOutlines.register();
    }
}
