package com.kltyton.companioncall.mixin.transfer;

import com.kltyton.companioncall.storage.transfer.StorageHooks;
import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.entity.navigation.EntityRecallNavigation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Restricts irreversible removal to outside the local transfer transaction. */
@Mixin(Entity.class)
public abstract class EntityLifecycleGuardMixin {
    @Inject(method = "setRemoved", at = @At("HEAD"), cancellable = true)
    private void companionCall$removal(Entity.RemovalReason reason, CallbackInfo callback) {
        if ((reason == Entity.RemovalReason.UNLOADED_TO_CHUNK || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER)
                && ((Entity)(Object)this).level() instanceof ServerLevel level) {
            CompanionCall.server(level.getServer()).captureAppearance((Entity)(Object)this);
        }
        if (reason != Entity.RemovalReason.UNLOADED_TO_CHUNK && reason != Entity.RemovalReason.UNLOADED_WITH_PLAYER
                && StorageHooks.quarantined((Entity)(Object)this)) callback.cancel();
    }
    @Inject(method = "restoreFrom", at = @At("RETURN"))
    private void companionCall$restored(Entity original, CallbackInfo callback) {
        StorageHooks.restored((Entity)(Object)this);
    }
    @Inject(method = "setRemoved", at = @At("RETURN"))
    private void companionCall$releaseNavigation(Entity.RemovalReason reason, CallbackInfo callback) {
        Entity entity = (Entity)(Object)this;
        if (entity.isRemoved() && entity.level() instanceof ServerLevel level) {
            EntityRecallNavigation.stop(entity);
            if (reason.shouldDestroy()) {
                var runtime = CompanionCall.activeServer(level.getServer());
                if (runtime != null && runtime.index.get(entity.getUUID()) != null) runtime.death(entity);
            }
        }
    }
    @Inject(method = "stopRiding", at = @At("HEAD"), cancellable = true)
    private void companionCall$dismount(CallbackInfo callback) {
        if (StorageHooks.quarantined((Entity)(Object)this)) callback.cancel();
    }
}
