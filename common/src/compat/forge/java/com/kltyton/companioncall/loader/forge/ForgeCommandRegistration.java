package com.kltyton.companioncall.loader.forge;

import com.kltyton.companioncall.command.CompanionCommands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "companion_call")
public final class ForgeCommandRegistration {
    private ForgeCommandRegistration() {}
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        CompanionCommands.register(event.getDispatcher());
    }
}
