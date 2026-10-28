package com.kltyton.companioncall.client.input;

import com.kltyton.companioncall.client.platform.ClientVersion;
import com.kltyton.companioncall.mixin.client.KeyMappingAccess;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

public final class ClientAccess {
    public static final KeyMapping KEY = ClientVersion.keyMapping();
    public static final KeyMapping ADD_RIDDEN = ClientVersion.addRiddenMapping();
    private ClientAccess() {}
    public static Screen screen() { return ClientVersion.screen(); }
    public static void screen(Screen screen) { ClientVersion.screen(screen); }
    public static boolean ready() { var mc = Minecraft.getInstance(); return mc.player != null && mc.level != null && mc.getConnection() != null; }
    public static boolean focused() { return Minecraft.getInstance().isWindowActive(); }
    public static Object connection() { return Minecraft.getInstance().getConnection(); }
    public static boolean grabbed() { return Minecraft.getInstance().mouseHandler.isMouseGrabbed(); }
    public static void restoreMouse(boolean grabbed) {
        var mouse = Minecraft.getInstance().mouseHandler;
        if (!grabbed) mouse.releaseMouse();
        else if (focused() && !mouse.isMouseGrabbed()) mouse.grabMouse();
    }
    public static boolean down() {
        InputConstants.Key key = ((KeyMappingAccess) KEY).companionCall$key();
        if (key.getValue() < 0) return false;
        return switch (key.getType()) {
            case KEYSYM -> ClientVersion.keyDown(key.getValue());
            case MOUSE -> GLFW.glfwGetMouseButton(ClientVersion.window(), key.getValue()) == GLFW.GLFW_PRESS;
            case SCANCODE -> scanCodeDown(key.getValue());
        };
    }
    private static boolean scanCodeDown(int scanCode) {
        for (int key = GLFW.GLFW_KEY_SPACE; key <= GLFW.GLFW_KEY_LAST; key++) {
            if (GLFW.glfwGetKeyScancode(key) == scanCode) return ClientVersion.keyDown(key);
        }
        return false;
    }
}
