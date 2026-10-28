package com.kltyton.companioncall.loader.neoforge;

import com.kltyton.companioncall.command.CompanionCommands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@EventBusSubscriber(modid = "companion_call")
public final class NeoForgeCommandRegistration {
    private NeoForgeCommandRegistration() {}
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        CompanionCommands.register(event.getDispatcher());
    }
}
