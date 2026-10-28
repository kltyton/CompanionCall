package com.kltyton.companioncall.mixin.lifecycle;

import com.kltyton.companioncall.CompanionCall;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Preserves controller and animation ticks without adding a second recall acceleration. */
@Mixin(LivingEntity.class)
public abstract class NativeTravelInputMixin {
    @ModifyVariable(method = "travel", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vec3 companionCall$sharedRecallInput(Vec3 input) {
        LivingEntity entity = (LivingEntity) (Object) this;
        if (!(entity.level() instanceof ServerLevel level)) return input;
        var runtime = CompanionCall.activeServer(level.getServer());
        boolean shared = runtime != null && runtime.recalls.usesSharedMotion(entity);
        return shared ? Vec3.ZERO : input;
    }
}
