package com.kltyton.companioncall.mixin.entity;

import com.kltyton.companioncall.entity.glow.RecallGlow;
import com.kltyton.companioncall.entity.glow.RecallGlowCarrier;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Synchronizes recall RGB while preserving the entity's original glow and scoreboard state. */
@Mixin(Entity.class)
public abstract class RecallGlowEntityMixin implements RecallGlowCarrier {
    @Unique private static final EntityDataAccessor<Integer> companionCall$RECALL_COLOR =
            SynchedEntityData.defineId(Entity.class, EntityDataSerializers.INT);

    @Inject(method = "<init>", at = @At("TAIL"))
    private void companionCall$defineRecallColor(CallbackInfo callback) {
        ((Entity) (Object) this).getEntityData().define(companionCall$RECALL_COLOR, RecallGlow.NONE);
    }

    @Override public int companionCall$recallColor() {
        return ((Entity) (Object) this).getEntityData().get(companionCall$RECALL_COLOR);
    }

    @Override public void companionCall$recallColor(int color) {
        ((Entity) (Object) this).getEntityData().set(companionCall$RECALL_COLOR, color);
    }

    @Inject(method = "isCurrentlyGlowing", at = @At("RETURN"), cancellable = true)
    private void companionCall$renderRecallGlow(CallbackInfoReturnable<Boolean> callback) {
        if (((Entity) (Object) this).level().isClientSide() && companionCall$recallColor() != RecallGlow.NONE) {
            callback.setReturnValue(true);
        }
    }

    @Inject(method = "getTeamColor", at = @At("RETURN"), cancellable = true)
    private void companionCall$renderRecallColor(CallbackInfoReturnable<Integer> callback) {
        int color = companionCall$recallColor();
        if (((Entity) (Object) this).level().isClientSide() && color != RecallGlow.NONE) {
            callback.setReturnValue(color);
        }
    }

    @Inject(method = "setRemoved", at = @At("RETURN"))
    private void companionCall$clearRemovedGlow(Entity.RemovalReason reason, CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        if (!entity.level().isClientSide() && entity.isRemoved()) companionCall$recallColor(RecallGlow.NONE);
    }
}
