package com.kltyton.companioncall.loader.fabric;

import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.config.CompanionConfigSpec;
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeConfigRegistry;
import net.neoforged.fml.config.ModConfig;

public final class FabricConfiguration {
    private FabricConfiguration() {}

    public static void register() {
        NeoForgeConfigRegistry.INSTANCE.register(CompanionCall.MOD_ID, ModConfig.Type.CLIENT,
                CompanionConfigSpec.CLIENT, "companion_call-client.toml");
        NeoForgeConfigRegistry.INSTANCE.register(CompanionCall.MOD_ID, ModConfig.Type.COMMON,
                CompanionConfigSpec.COMMON, "companion_call-common.toml");
    }
}
