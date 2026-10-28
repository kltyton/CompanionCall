package com.kltyton.companioncall.client.preview;

import com.kltyton.companioncall.storage.pet.PetRecord;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.kltyton.kltytonui.layout.Position;
import io.github.kltyton.kltytonui.layout.Size;
import io.github.kltyton.kltytonui.render.Base;
import io.github.kltyton.kltytonui.render.Rect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.joml.Quaternionf;

public final class ClientPreviewRenderer {
    private static final int FULL_BRIGHT = 15728880;
    private final PreviewEntity model = new PreviewEntity();
    private boolean rendered;

    public void update(PetRecord pet) { model.update(pet); }
    public void close() { model.close(); rendered = false; }
    public boolean isAvailable() { return rendered; }

    public void render(PoseStack poseStack, Rect rect, boolean portrait) {
        rendered = false;
        Entity entity = model.current(portrait);
        if (entity == null) return;
        Position position = rect.getBodyRectPosition();
        Size size = rect.getBodyRectSize();
        if (size.width() <= 0 || size.height() <= 0) return;

        float partialTick = ClientPreviewClock.partialTick();
        PreviewFraming frame = PreviewFraming.fit(entity.getBbWidth(), entity.getBbHeight(), entity.getEyeHeight(),
                portrait && entity instanceof LivingEntity, size);
        float scale = frame.scale();
        if (!Float.isFinite(scale) || scale <= 0.0F) return;

        Minecraft minecraft = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        float centerX = (float) (position.x + size.width() / 2.0D);
        float centerY = (float) (position.y + size.height() / 2.0D);

        Base.commitDraws();
        Lighting.setupForEntityInInventory();
        poseStack.pushPose();
        try {
            poseStack.translate(centerX, centerY, Base.getGuiItemModelZ());
            poseStack.scale(scale, scale, -scale);
            poseStack.translate(-frame.centerX(), frame.centerY(), 0.0F);
            poseStack.mulPose(new Quaternionf().rotateZ((float) Math.PI));
            dispatcher.setRenderShadow(false);
            try {
                RenderSystem.runAsFancy(() -> dispatcher.render(
                        entity, 0.0D, 0.0D, 0.0D, 0.0F, partialTick, poseStack, buffers, FULL_BRIGHT));
            } finally {
                dispatcher.setRenderShadow(true);
            }
            buffers.endBatch();
            rendered = true;
        } catch (RuntimeException error) {
            model.failed(error);
        } finally {
            poseStack.popPose();
            Lighting.setupFor3DItems();
            Base.commitDraws();
        }
    }
}
