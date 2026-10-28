package com.kltyton.companioncall.client.platform;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

public final class ClientVersion {
    private ClientVersion() {}
    public static KeyMapping keyMapping() {
        return new KeyMapping("key.companion_call.recall", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.misc");
    }
    public static KeyMapping addRiddenMapping() {
        return new KeyMapping("key.companion_call.add_ridden", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.misc");
    }
    public static Screen screen() { return Minecraft.getInstance().screen; }
    public static void screen(Screen screen) { Minecraft.getInstance().setScreen(screen); }
    public static long window() { return Minecraft.getInstance().getWindow().getWindow(); }
    public static boolean keyDown(int key) { return InputConstants.isKeyDown(window(), key); }
}
