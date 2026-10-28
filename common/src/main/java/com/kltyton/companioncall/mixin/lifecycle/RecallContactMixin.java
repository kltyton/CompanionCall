package com.kltyton.companioncall.mixin.lifecycle;

import com.kltyton.companioncall.CompanionCall;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the authorized rider at swept physical contact through the native movement call. */
@Mixin(Entity.class)
public abstract class RecallContactMixin {
    @ModifyVariable(method = "move", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vec3 companionCall$captureMovement(Vec3 movement) {
        Entity entity = (Entity) (Object) this;
        if (!(entity.level() instanceof ServerLevel level)) return movement;
        var runtime = CompanionCall.activeServer(level.getServer());
        return runtime == null ? movement : runtime.recalls.captureMotion(entity, movement);
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void companionCall$contact(CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        if (entity.level() instanceof ServerLevel level) {
            var runtime = CompanionCall.activeServer(level.getServer());
            if (runtime != null) runtime.recalls.contact(entity);
        }
    }
}
