package com.kltyton.companioncall.client.preview;

import net.minecraft.client.Minecraft;

public final class ClientPreviewClock {
    private ClientPreviewClock() {}

    public static float partialTick() {
        return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
    }
}
