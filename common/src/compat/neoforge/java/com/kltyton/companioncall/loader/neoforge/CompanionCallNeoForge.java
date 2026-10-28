package com.kltyton.companioncall.loader.neoforge;
import com.kltyton.companioncall.CompanionCall;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import com.kltyton.companioncall.config.CompanionConfigSpec;
@Mod("companion_call")
public final class CompanionCallNeoForge {
    public CompanionCallNeoForge(ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, CompanionConfigSpec.CLIENT, "companion_call-client.toml");
        container.registerConfig(ModConfig.Type.COMMON, CompanionConfigSpec.COMMON, "companion_call-common.toml");
        CompanionCall.initialize();
    }
}
