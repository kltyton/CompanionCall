package com.kltyton.companioncall.mixin.entity;

import com.kltyton.companioncall.entity.navigation.BoardingApproach;
import com.kltyton.companioncall.entity.navigation.BoardingApproachCarrier;
import com.kltyton.companioncall.loader.neoforge.entity.RecallEntityAttachments;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies the native attachment boarding pair without changing other collision responses. */
@Mixin(Entity.class)
public abstract class BoardingApproachMixin implements BoardingApproachCarrier {
    @Override public String companionCall$boardingOwner() {
        Entity entity = (Entity) (Object) this;
        return entity.hasData(RecallEntityAttachments.BOARDING_OWNER)
                ? entity.getData(RecallEntityAttachments.BOARDING_OWNER) : "";
    }

    @Override public void companionCall$boardingOwner(String owner) {
        if (companionCall$boardingOwner().equals(owner)) return;
        Entity entity = (Entity) (Object) this;
        if (owner.isEmpty()) entity.removeData(RecallEntityAttachments.BOARDING_OWNER);
        else entity.setData(RecallEntityAttachments.BOARDING_OWNER, owner);
    }

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void companionCall$allowBoardingApproach(Entity other, CallbackInfo callback) {
        if (BoardingApproach.matches((Entity) (Object) this, other)) callback.cancel();
    }

    @Inject(method = "setRemoved", at = @At("RETURN"))
    private void companionCall$clearBoarding(Entity.RemovalReason reason, CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        if (!entity.level().isClientSide() && entity.isRemoved()) companionCall$boardingOwner("");
    }
}
