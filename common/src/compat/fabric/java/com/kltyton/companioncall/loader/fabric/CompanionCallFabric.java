package com.kltyton.companioncall.loader.fabric;
import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.command.CompanionCommands;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
public final class CompanionCallFabric implements ModInitializer {
    @Override public void onInitialize() {
        FabricConfiguration.register();
        CompanionCall.initialize();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> CompanionCommands.register(dispatcher));
    }
}
