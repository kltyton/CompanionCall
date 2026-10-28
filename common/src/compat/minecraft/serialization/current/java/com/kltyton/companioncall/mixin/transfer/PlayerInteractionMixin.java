package com.kltyton.companioncall.mixin.transfer;

import com.kltyton.companioncall.storage.transfer.StorageHooks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents a held entity from exposing equipment or inventory before transfer commit. */
@Mixin(Player.class)
public abstract class PlayerInteractionMixin {
    @Inject(method = "interactOn", at = @At("HEAD"), cancellable = true)
    private void companionCall$interaction(Entity target, InteractionHand hand, Vec3 location, CallbackInfoReturnable<InteractionResult> callback) {
        if (StorageHooks.quarantined(target) || StorageHooks.quarantined((Entity)(Object)this)) callback.setReturnValue(InteractionResult.FAIL);
    }
}
