package com.kltyton.companioncall.client.preview;

import io.github.kltyton.kltytonui.neoforge.RenderService;
import net.minecraft.client.renderer.SubmitNodeStorage;

/** Keeps native model passes on KUI's active output target and clipping state. */
final class PreviewNativeFeatures {
    private PreviewNativeFeatures() {}

    static void draw(SubmitNodeStorage nodes) {
        RenderService.INSTANCE.dispatchNativeFeatures(nodes);
    }
}
