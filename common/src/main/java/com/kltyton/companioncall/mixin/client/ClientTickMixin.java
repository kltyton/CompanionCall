package com.kltyton.companioncall.mixin.client;

import com.kltyton.companioncall.client.state.CompanionClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Samples the registered game binding and updates the two KUI hosts on the client thread. */
@Mixin(Minecraft.class)
public abstract class ClientTickMixin {
    @Inject(method = "tick", at = @At("RETURN"))
    private void companionCall$tick(CallbackInfo callback) { CompanionClient.tick(); }
}
