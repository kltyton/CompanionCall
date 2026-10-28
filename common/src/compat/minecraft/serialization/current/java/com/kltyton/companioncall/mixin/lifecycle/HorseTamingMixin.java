package com.kltyton.companioncall.mixin.lifecycle;

import com.kltyton.companioncall.CompanionCall;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Registers a successful horse tame at the actual tame event, before later chunk observations. */
@Mixin(AbstractHorse.class)
public abstract class HorseTamingMixin {
    @Inject(method = "tameWithName", at = @At("RETURN"))
    private void companionCall$tamed(Player owner, CallbackInfoReturnable<Boolean> callback) {
        var entity = (Entity)(Object)this;
        if (callback.getReturnValueZ() && entity.level() instanceof ServerLevel level) CompanionCall.server(level.getServer()).observe(entity);
    }
}
