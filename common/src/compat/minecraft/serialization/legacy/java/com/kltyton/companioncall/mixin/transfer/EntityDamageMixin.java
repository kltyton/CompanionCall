package com.kltyton.companioncall.mixin.transfer;

import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.storage.transfer.StorageHooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Restricts arrival protection to fall and drowning, in addition to the brief transfer hold. */
@Mixin(LivingEntity.class)
public abstract class EntityDamageMixin {
    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void companionCall$damage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> callback) {
        var entity = (Entity)(Object)this;
        if (StorageHooks.quarantined(entity)) {
            callback.setReturnValue(false);
        } else if (entity.level() instanceof ServerLevel level
                && (source.is(DamageTypes.FALL) || source.is(DamageTypes.DROWN))
                && CompanionCall.server(level.getServer()).protectedFromEnvironmentalDamage(entity.getUUID())) {
            callback.setReturnValue(false);
        }
    }
}
