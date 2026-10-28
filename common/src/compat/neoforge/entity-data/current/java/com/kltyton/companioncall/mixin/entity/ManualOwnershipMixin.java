package com.kltyton.companioncall.mixin.entity;

import com.kltyton.companioncall.entity.ownership.ManualOwnershipCarrier;
import com.kltyton.companioncall.loader.neoforge.entity.RecallEntityAttachments;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Persists explicit recall ownership in native attachments without changing taming contracts. */
@Mixin(Entity.class)
public abstract class ManualOwnershipMixin implements ManualOwnershipCarrier {
    @Override public UUID companionCall$manualOwner() {
        Entity entity = (Entity) (Object) this;
        return entity.hasData(RecallEntityAttachments.MANUAL_OWNER)
                ? entity.getData(RecallEntityAttachments.MANUAL_OWNER).orElse(null) : null;
    }

    @Override public void companionCall$manualOwner(UUID owner) {
        if (java.util.Objects.equals(companionCall$manualOwner(), owner)) return;
        Entity entity = (Entity) (Object) this;
        if (owner == null) entity.removeData(RecallEntityAttachments.MANUAL_OWNER);
        else entity.setData(RecallEntityAttachments.MANUAL_OWNER, Optional.of(owner));
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void companionCall$restoreContinuousPosition(ValueInput input, CallbackInfo callback) {
        Entity entity = (Entity) (Object) this;
        if (entity instanceof BlockAttachedEntity && companionCall$manualOwner() != null) {
            input.read("Pos", Vec3.CODEC).ifPresent(entity::setPos);
        }
    }
}
