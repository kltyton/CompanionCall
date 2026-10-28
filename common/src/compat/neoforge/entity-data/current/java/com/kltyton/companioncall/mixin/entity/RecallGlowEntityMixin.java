package com.kltyton.companioncall.mixin.entity;

import com.kltyton.companioncall.entity.glow.RecallGlow;
import com.kltyton.companioncall.entity.glow.RecallGlowCarrier;
import com.kltyton.companioncall.loader.neoforge.entity.RecallEntityAttachments;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Combines native recall attachments with the original outline and team color. */
@Mixin(Entity.class)
public abstract class RecallGlowEntityMixin implements RecallGlowCarrier {
    @Override public int companionCall$recallColor() {
        Entity entity = (Entity) (Object) this;
        return entity.hasData(RecallEntityAttachments.RECALL_COLOR)
                ? entity.getData(RecallEntityAttachments.RECALL_COLOR) : RecallGlow.NONE;
    }

    @Override public void companionCall$recallColor(int color) {
        if (companionCall$recallColor() == color) return;
        Entity entity = (Entity) (Object) this;
        if (color == RecallGlow.NONE) entity.removeData(RecallEntityAttachments.RECALL_COLOR);
        else entity.setData(RecallEntityAttachments.RECALL_COLOR, color);
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
