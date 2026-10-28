package com.kltyton.companioncall.client.preview;

import com.kltyton.companioncall.storage.pet.PetRecord;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.kltyton.kltytonui.init.Document;
import io.github.kltyton.kltytonui.init.Element;
import io.github.kltyton.kltytonui.render.Base;
import io.github.kltyton.kltytonui.render.Drawer;
import io.github.kltyton.kltytonui.render.Rect;
import java.util.Objects;
import java.util.UUID;

public final class CompanionPreviewElement extends Element {
    private final ClientPreviewRenderer renderer = new ClientPreviewRenderer();
    private UUID petId;
    private UUID preparedId;
    private int appliedVersion = -1;
    private boolean everConnected;

    public CompanionPreviewElement(Document document) {
        super(document, CompanionPreview.TAG_NAME);
        CompanionPreview.track(this);
    }

    @Override
    protected void onInitFromDom(Element origin) {
        syncPetId();
    }

    @Override
    public void setAttribute(String name, String value) {
        super.setAttribute(name, value);
        if ("pet-id".equals(name)) {
            appliedVersion = -1;
            syncPetId();
        }
    }

    @Override
    public void removeAttribute(String name) {
        super.removeAttribute(name);
        if ("pet-id".equals(name)) {
            appliedVersion = -1;
            syncPetId();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!isConnected()) {
            if (everConnected) release();
            return;
        }
        everConnected = true;
        CompanionPreview.track(this);
        refresh();
    }

    @Override
    public void drawPhase(PoseStack poseStack, Base.RenderPhase phase) {
        Rect rect = Rect.of(this);
        switch (phase) {
            case SHADOW -> rect.drawShadow(poseStack);
            case BODY -> {
                rect.drawBody(poseStack);
                refresh();
                renderer.render(poseStack, rect, "portrait".equals(getAttribute("view")));
                String state = renderer.isAvailable() ? "ready" : "unavailable";
                if (!state.equals(getAttribute("data-preview-state"))) {
                    setAttribute("data-preview-state", state);
                }
            }
            case BORDER -> rect.drawBorder(poseStack);
            default -> {
            }
        }
    }

    void release() {
        renderer.close();
        CompanionPreview.untrack(this);
    }

    boolean detached() {
        return everConnected && !isConnected();
    }

    private void refresh() {
        int version = CompanionPreview.version();
        if (version == appliedVersion && Objects.equals(petId, preparedId)) return;
        appliedVersion = version;
        preparedId = petId;
        PetRecord record = CompanionPreview.pet(petId);
        if (record == null) renderer.close();
        else renderer.update(record);
        if (document != null && isConnected()) document.markDirty(this, Drawer.REPAINT);
    }

    private void syncPetId() {
        String raw = getAttribute("pet-id");
        if (raw == null || raw.isBlank()) {
            petId = null;
            return;
        }
        try {
            petId = UUID.fromString(raw.trim());
        } catch (IllegalArgumentException invalid) {
            petId = null;
        }
    }
}
