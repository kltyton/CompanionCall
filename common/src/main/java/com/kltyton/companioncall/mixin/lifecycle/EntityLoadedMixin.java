package com.kltyton.companioncall.mixin.lifecycle;

import com.kltyton.companioncall.CompanionCall;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Discovers existing pets only when the original entity is actually accepted by its level. */
@Mixin(PersistentEntitySectionManager.class)
public abstract class EntityLoadedMixin {
    @Inject(method = "addEntity", at = @At("RETURN"))
    private void companionCall$loaded(EntityAccess access, boolean loaded, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ() && access instanceof Entity entity && entity.level() instanceof ServerLevel level) {
            CompanionCall.server(level.getServer()).captureAppearance(entity);
        }
    }
}
