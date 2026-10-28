package com.kltyton.companioncall.loader.fabric;

import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.config.CompanionConfigSpec;
import fuzs.forgeconfigapiport.api.config.v2.ForgeConfigRegistry;
import net.minecraftforge.fml.config.ModConfig;

public final class FabricConfiguration {
    private FabricConfiguration() {}

    public static void register() {
        ForgeConfigRegistry.INSTANCE.register(CompanionCall.MOD_ID, ModConfig.Type.CLIENT,
                CompanionConfigSpec.CLIENT, "companion_call-client.toml");
        ForgeConfigRegistry.INSTANCE.register(CompanionCall.MOD_ID, ModConfig.Type.COMMON,
                CompanionConfigSpec.COMMON, "companion_call-common.toml");
    }
}
