package com.kltyton.companioncall.loader.fabric;
import com.kltyton.companioncall.client.input.ClientAccess;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
final class FabricKeyRegistration {
    private FabricKeyRegistration() {}
    static void register() {
        KeyMappingHelper.registerKeyMapping(ClientAccess.KEY);
        KeyMappingHelper.registerKeyMapping(ClientAccess.ADD_RIDDEN);
    }
}
