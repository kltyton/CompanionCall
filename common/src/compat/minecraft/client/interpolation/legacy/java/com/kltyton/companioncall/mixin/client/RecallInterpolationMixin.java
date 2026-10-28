package com.kltyton.companioncall.mixin.client;

import com.kltyton.companioncall.entity.glow.RecallGlow;
import com.kltyton.companioncall.entity.glow.RecallGlowCarrier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Aligns native living and vehicle interpolation with per-tick recall position updates. */
@Mixin({LivingEntity.class, Boat.class, AbstractMinecart.class})
public abstract class RecallInterpolationMixin {
    @Unique private boolean companionCall$isRecalling() {
        Entity entity = (Entity) (Object) this;
        return entity.level().isClientSide() && entity instanceof RecallGlowCarrier carrier
                && carrier.companionCall$recallColor() != RecallGlow.NONE;
    }

    @ModifyVariable(method = "lerpTo", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private int companionCall$recallSteps(int steps) {
        return companionCall$isRecalling() && steps > 1 ? 1 : steps;
    }

    // Boat ignores the packet duration; minecart adds two ticks. These constants occur on different targets.
    @ModifyConstant(method = "lerpTo", constant = @Constant(intValue = 10), require = 0)
    private int companionCall$boatSteps(int steps) { return companionCall$isRecalling() ? 1 : steps; }

    @ModifyConstant(method = "lerpTo", constant = @Constant(intValue = 2), require = 0)
    private int companionCall$minecartPadding(int padding) { return companionCall$isRecalling() ? 0 : padding; }
}
