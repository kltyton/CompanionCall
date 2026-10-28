package com.kltyton.companioncall.loader.fabric;

import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.config.CompanionConfigSpec;
import fuzs.forgeconfigapiport.fabric.api.v5.ConfigRegistry;
import net.neoforged.fml.config.ModConfig;

public final class FabricConfiguration {
    private FabricConfiguration() {}

    public static void register() {
        ConfigRegistry.INSTANCE.register(CompanionCall.MOD_ID, ModConfig.Type.CLIENT,
                CompanionConfigSpec.CLIENT, "companion_call-client.toml");
        ConfigRegistry.INSTANCE.register(CompanionCall.MOD_ID, ModConfig.Type.COMMON,
                CompanionConfigSpec.COMMON, "companion_call-common.toml");
    }
}
