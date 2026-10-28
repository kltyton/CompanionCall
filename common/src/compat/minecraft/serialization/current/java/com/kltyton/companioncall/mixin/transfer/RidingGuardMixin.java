package com.kltyton.companioncall.mixin.transfer;

import com.kltyton.companioncall.storage.transfer.StorageHooks;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Holds the transaction's passenger graph stable while native migration is pending. */
@Mixin(Entity.class)
public abstract class RidingGuardMixin {
    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;ZZ)Z", at = @At("HEAD"), cancellable = true)
    private void companionCall$boarding(Entity vehicle, boolean force, boolean sendEvents, CallbackInfoReturnable<Boolean> callback) {
        if (StorageHooks.quarantined((Entity)(Object)this) || StorageHooks.quarantined(vehicle)) callback.setReturnValue(false);
    }
}
