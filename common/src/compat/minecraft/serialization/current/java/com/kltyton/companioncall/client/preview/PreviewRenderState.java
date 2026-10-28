package com.kltyton.companioncall.client.preview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Entity;

final class PreviewRenderState {
    private PreviewRenderState() {}

    static EntityRenderState extract(PreviewEntity model, boolean portrait) {
        Entity entity = model.current(portrait);
        if (entity == null) return null;
        try {
            float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
            EntityRenderState state = Minecraft.getInstance().getEntityRenderDispatcher().extractEntity(entity, partial);
            state.lightCoords = 15728880;
            state.shadowPieces.clear();
            state.outlineColor = 0;
            state.nameTag = null;
            state.scoreText = null;
            state.displayFireAnimation = false;
            if (state instanceof LivingEntityRenderState living) {
                living.bodyRot = entity.getYRot();
                living.yRot = 0;
                living.xRot = 0;
                if (living.scale > 1.0E-4F) {
                    living.boundingBoxWidth /= living.scale;
                    living.boundingBoxHeight /= living.scale;
                    living.eyeHeight /= living.scale;
                }
                living.scale = 1;
            }
            return state;
        } catch (RuntimeException error) {
            model.failed(error);
            return null;
        }
    }
}
