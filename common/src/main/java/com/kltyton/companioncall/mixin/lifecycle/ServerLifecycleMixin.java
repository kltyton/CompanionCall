package com.kltyton.companioncall.mixin.lifecycle;

import com.kltyton.companioncall.CompanionCall;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Runs recall work after native world ticks and closes metadata after native server saving. */
@Mixin(MinecraftServer.class)
public abstract class ServerLifecycleMixin {
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void companionCall$tick(CallbackInfo callback) { CompanionCall.server((MinecraftServer)(Object)this).tick(); }
    @Inject(method = "stopServer", at = @At("RETURN"))
    private void companionCall$stopped(CallbackInfo callback) { CompanionCall.stop((MinecraftServer)(Object)this); }
}
