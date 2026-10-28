package com.kltyton.companioncall.mixin.transfer;

import com.kltyton.companioncall.storage.transfer.LegacyTeleportTarget;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.PortalInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies only this recall's destination to the original dimension-change implementation. */
@Mixin(Entity.class)
public abstract class LegacyPortalMixin {
    @Inject(method = "findDimensionEntryPoint", at = @At("HEAD"), cancellable = true)
    private void companionCall$destination(ServerLevel level, CallbackInfoReturnable<PortalInfo> callback) {
        var target = LegacyTeleportTarget.current();
        if (target != null) callback.setReturnValue(target);
    }
}
