package com.kltyton.companioncall.mixin.lifecycle;

import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.entity.navigation.RecallMotionIntent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps native animation facing on the exact shared motion after navigation updates its grid nodes. */
@Mixin(Mob.class)
public abstract class RecallMotionIntentMixin {
    @Inject(method = "serverAiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/control/MoveControl;tick()V"))
    private void companionCall$alignMotionIntent(CallbackInfo callback) {
        Mob mob = (Mob) (Object) this;
        if (!(mob.level() instanceof ServerLevel level)) return;
        var runtime = CompanionCall.activeServer(level.getServer());
        if (runtime != null && runtime.recalls.usesSharedMotion(mob)) RecallMotionIntent.alignWithSharedMotion(mob);
    }
}
