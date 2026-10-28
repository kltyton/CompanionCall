package com.kltyton.companioncall.mixin.transfer;

import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.storage.transfer.StorageHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents death side effects during transfer and invalidates only a completed native death. */
@Mixin(LivingEntity.class)
public abstract class LivingStateGuardMixin {
    @Shadow protected boolean dead;
    @Inject(method = "remove", at = @At("HEAD"), cancellable = true)
    private void companionCall$removal(Entity.RemovalReason reason, CallbackInfo callback) {
        if ((reason == Entity.RemovalReason.KILLED || reason == Entity.RemovalReason.DISCARDED)
                && StorageHooks.quarantined((Entity)(Object)this)) callback.cancel();
    }
    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void companionCall$health(float value, CallbackInfo callback) {
        if (StorageHooks.quarantined((Entity)(Object)this)) callback.cancel();
    }
    @Inject(method = "die", at = @At("HEAD"), cancellable = true)
    private void companionCall$holdDeath(DamageSource source, CallbackInfo callback) {
        if (StorageHooks.quarantined((Entity)(Object)this)) callback.cancel();
    }
    @Inject(method = "die", at = @At("RETURN"))
    private void companionCall$dead(DamageSource source, CallbackInfo callback) {
        var entity = (Entity)(Object)this;
        if (dead && entity.level() instanceof ServerLevel level) CompanionCall.server(level.getServer()).death(entity);
    }
}
