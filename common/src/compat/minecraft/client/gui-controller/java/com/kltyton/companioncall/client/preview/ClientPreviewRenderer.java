package com.kltyton.companioncall.client.preview;

import com.kltyton.companioncall.storage.pet.PetRecord;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.kltyton.kltytonui.layout.Position;
import io.github.kltyton.kltytonui.layout.Size;
import io.github.kltyton.kltytonui.render.Base;
import io.github.kltyton.kltytonui.render.Rect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.joml.Quaternionf;

public final class ClientPreviewRenderer {
    private final PreviewEntity model = new PreviewEntity();
    private boolean rendered;

    public void update(PetRecord pet) { model.update(pet); }
    public void close() { model.close(); rendered = false; }
    public boolean isAvailable() { return rendered; }

    public void render(PoseStack poseStack, Rect rect, boolean portrait) {
        rendered = false;
        Position position = rect.getBodyRectPosition();
        Size size = rect.getBodyRectSize();
        if (size.width() <= 0 || size.height() <= 0) return;

        EntityRenderState current = PreviewRenderState.extract(model, portrait);
        if (current == null) return;

        PreviewFraming frame = PreviewFraming.fit(current.boundingBoxWidth, current.boundingBoxHeight, current.eyeHeight,
                portrait && current instanceof LivingEntityRenderState, size);
        float scale = frame.scale();
        if (!Float.isFinite(scale) || scale <= 0.0F) return;

        Minecraft minecraft = Minecraft.getInstance();
        EntityRenderDispatcher dispatcher = minecraft.getEntityRenderDispatcher();
        float centerX = (float) (position.x + size.width() / 2.0D);
        float centerY = (float) (position.y + size.height() / 2.0D);

        Base.commitDraws();
        minecraft.gameRenderer.lighting().setupFor(Lighting.Entry.ENTITY_IN_UI);
        poseStack.pushPose();
        try {
            poseStack.translate(centerX, centerY, Base.getGuiItemModelZ());
            poseStack.scale(scale, scale, -scale);
            poseStack.translate(-frame.centerX(), frame.centerY(), 0.0F);
            poseStack.mulPose(new Quaternionf().rotateZ((float) Math.PI));
            SubmitNodeStorage nodes = new SubmitNodeStorage();
            dispatcher.submit(
                    current,
                    new CameraRenderState(),
                    0.0D,
                    0.0D,
                    0.0D,
                    poseStack,
                    nodes
            );
            PreviewNativeFeatures.draw(nodes);
            rendered = true;
        } catch (RuntimeException error) {
            model.failed(error);
        } finally {
            poseStack.popPose();
            Base.commitDraws();
        }
    }
}
