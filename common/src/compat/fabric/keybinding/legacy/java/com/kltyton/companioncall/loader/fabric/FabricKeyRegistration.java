package com.kltyton.companioncall.loader.fabric;
import com.kltyton.companioncall.client.input.ClientAccess;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
final class FabricKeyRegistration {
    private FabricKeyRegistration() {}
    static void register() {
        KeyBindingHelper.registerKeyBinding(ClientAccess.KEY);
        KeyBindingHelper.registerKeyBinding(ClientAccess.ADD_RIDDEN);
    }
}
