package com.kltyton.companioncall.mixin.client;

import com.kltyton.companioncall.entity.glow.RecallGlow;
import com.kltyton.companioncall.entity.glow.RecallGlowCarrier;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Bounds active recall interpolation to one tick without replacing configured durations. */
@Mixin(InterpolationHandler.class)
public abstract class RecallInterpolationMixin {
    @Shadow @Final private Entity entity;

    @ModifyExpressionValue(method = "interpolateTo", at = @At(value = "FIELD",
            target = "Lnet/minecraft/world/entity/InterpolationHandler;interpolationSteps:I"))
    private int companionCall$recallDuration(int configured) {
        boolean recalling = entity.level().isClientSide() && entity instanceof RecallGlowCarrier carrier
                && carrier.companionCall$recallColor() != RecallGlow.NONE;
        return recalling && configured > 1 ? 1 : configured;
    }
}
