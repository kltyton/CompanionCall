package com.kltyton.companioncall.mixin.entity;

import com.kltyton.companioncall.entity.navigation.BoardingApproach;
import com.kltyton.companioncall.entity.navigation.BoardingApproachCarrier;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Suppresses mutual separation only for the synchronized final boarding pair. */
@Mixin(Entity.class)
public abstract class BoardingApproachMixin implements BoardingApproachCarrier {
    @Unique private static final EntityDataAccessor<String> companionCall$BOARDING_OWNER =
            SynchedEntityData.defineId(Entity.class, EntityDataSerializers.STRING);

    @Inject(method = "<init>", at = @At("TAIL"))
    private void companionCall$defineBoarding(CallbackInfo callback) {
        ((Entity) (Object) this).getEntityData().define(companionCall$BOARDING_OWNER, "");
    }

    @Override public String companionCall$boardingOwner() {
        return ((Entity) (Object) this).getEntityData().get(companionCall$BOARDING_OWNER);
    }

    @Override public void companionCall$boardingOwner(String owner) {
        ((Entity) (Object) this).getEntityData().set(companionCall$BOARDING_OWNER, owner);
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
