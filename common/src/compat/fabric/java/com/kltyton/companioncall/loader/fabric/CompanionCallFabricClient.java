package com.kltyton.companioncall.loader.fabric;
import com.kltyton.companioncall.client.state.CompanionClient;
import net.fabricmc.api.ClientModInitializer;
public final class CompanionCallFabricClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        CompanionClient.initialize();
        FabricKeyRegistration.register();
    }
}
