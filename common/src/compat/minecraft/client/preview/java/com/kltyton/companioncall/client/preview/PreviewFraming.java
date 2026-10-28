package com.kltyton.companioncall.client.preview;

import io.github.kltyton.kltytonui.layout.Size;

/** Orthographic framing for a full model or an eye-height portrait. */
record PreviewFraming(float scale, float centerX, float centerY) {
    static PreviewFraming fit(float width, float height, float eyeHeight, boolean portrait, Size viewport) {
        double modelWidth = Math.max(0.1, width);
        double modelHeight = Math.max(0.1, height);
        double frameWidth;
        double frameHeight;
        double centerY;
        if (portrait) {
            frameWidth = Math.max(modelWidth * 1.2, modelHeight * 0.65);
            frameHeight = frameWidth;
            centerY = eyeHeight;
        } else {
            double yaw = Math.toRadians(PreviewEntity.BODY_ROTATION);
            // Match vanilla's half-block visual-culling margin around the collision volume.
            frameWidth = modelWidth * (Math.abs(Math.cos(yaw)) + Math.abs(Math.sin(yaw))) + 1.0;
            frameHeight = modelHeight + 1.0;
            centerY = modelHeight / 2.0;
        }
        float scale = (float) Math.min(viewport.width() * 0.9 / frameWidth, viewport.height() * 0.9 / frameHeight);
        return new PreviewFraming(scale, 0, (float) centerY);
    }
}
