package com.kltyton.companioncall.client.compat.geckolib;

import com.geckolib.event.entity.CompileEntityRenderLayersEvent;
import com.geckolib.event.object.CompileObjectRenderLayersEvent;
import com.geckolib.event.replacedentity.CompileReplacedEntityRenderLayersEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Registers through GeckoLib's public layer events only when GeckoLib is installed. */
public final class GeckoRecallOutlines {
    private GeckoRecallOutlines() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(GeckoRecallOutlines::entityLayers);
        NeoForge.EVENT_BUS.addListener(GeckoRecallOutlines::replacedLayers);
        NeoForge.EVENT_BUS.addListener(GeckoRecallOutlines::objectLayers);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void entityLayers(CompileEntityRenderLayersEvent event) {
        event.addLayer(new RecallOutlineLayer(event.getRenderer()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void replacedLayers(CompileReplacedEntityRenderLayersEvent event) {
        event.addLayer(new RecallOutlineLayer(event.getRenderer()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void objectLayers(CompileObjectRenderLayersEvent event) {
        event.addLayer(new RecallOutlineLayer(event.getRenderer()));
    }
}
