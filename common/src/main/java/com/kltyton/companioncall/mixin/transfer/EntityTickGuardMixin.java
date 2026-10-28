package com.kltyton.companioncall.mixin.transfer;

import com.kltyton.companioncall.storage.transfer.StorageHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holds only the entities participating in an unfinished transfer. */
@Mixin(ServerLevel.class)
public abstract class EntityTickGuardMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void companionCall$entity(Entity entity, CallbackInfo callback) {
        if (StorageHooks.quarantined(entity)) callback.cancel();
    }
    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void companionCall$passenger(Entity vehicle, Entity entity, CallbackInfo callback) {
        if (StorageHooks.quarantined(entity)) callback.cancel();
    }
}
