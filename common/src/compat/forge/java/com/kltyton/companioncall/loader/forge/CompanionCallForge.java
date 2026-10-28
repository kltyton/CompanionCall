package com.kltyton.companioncall.loader.forge;
import com.kltyton.companioncall.CompanionCall;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import com.kltyton.companioncall.config.CompanionConfigSpec;
@Mod("companion_call")
public final class CompanionCallForge {
    public CompanionCallForge() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, CompanionConfigSpec.CLIENT, "companion_call-client.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CompanionConfigSpec.COMMON, "companion_call-common.toml");
        CompanionCall.initialize();
    }
}
