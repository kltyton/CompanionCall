package com.kltyton.companioncall.client.compat.geckolib;

import com.geckolib.animatable.GeoAnimatable;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.renderer.base.GeoRenderer;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.GeoRenderLayer;
import com.kltyton.companioncall.entity.glow.RecallGlow;
import com.kltyton.companioncall.entity.glow.RecallGlowCarrier;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.Entity;

/** Custom geometry needs an explicit outline submission in the 26.2 render pipeline. */
final class RecallOutlineLayer<T extends GeoAnimatable, O, R extends GeoRenderState> extends GeoRenderLayer<T, O, R> {
    private static final DataTicket<Integer> COLOR = DataTicket.create("companion_call:recall_outline", Integer.class);

    RecallOutlineLayer(GeoRenderer<T, O, R> renderer) { super(renderer); }

    @Override public void addRenderData(T animatable, O relatedObject, R state, float partialTick) {
        Entity entity = animatable instanceof Entity animated ? animated : relatedObject instanceof Entity related ? related : null;
        int color = entity instanceof RecallGlowCarrier carrier ? carrier.companionCall$recallColor() : RecallGlow.NONE;
        state.addGeckolibData(COLOR, color);
    }

    @Override public void submitRenderTask(RenderPassInfo<R> pass, SubmitNodeCollector tasks) {
        int color = pass.getOrDefaultGeckolibData(COLOR, RecallGlow.NONE);
        if (color == RecallGlow.NONE || !pass.willRender() || pass.model().isMissingno()) return;
        var outline = RenderTypes.outline(getTextureResource(pass.renderState()));
        tasks.submitCustomGeometry(pass.poseStack(), outline, (pose, vertices) -> {
            var matrices = pass.poseStack();
            matrices.pushPose();
            try {
                matrices.last().set(pose);
                pass.renderPosed(() -> pass.model().render(pass, vertices, pass.packedLight(),
                        pass.packedOverlay(), ARGB.opaque(color)));
            } finally {
                matrices.popPose();
            }
        });
    }
}
