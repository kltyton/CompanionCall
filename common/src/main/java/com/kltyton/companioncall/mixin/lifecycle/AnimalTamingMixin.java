package com.kltyton.companioncall.mixin.lifecycle;

import com.kltyton.companioncall.CompanionCall;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records new tames in the order their real tame operations complete. */
@Mixin(TamableAnimal.class)
public abstract class AnimalTamingMixin {
    @Inject(method = "tame", at = @At("RETURN"))
    private void companionCall$tamed(Player owner, CallbackInfo callback) {
        var entity = (Entity)(Object)this;
        if (entity.level() instanceof ServerLevel level) CompanionCall.server(level.getServer()).observe(entity);
    }
}
