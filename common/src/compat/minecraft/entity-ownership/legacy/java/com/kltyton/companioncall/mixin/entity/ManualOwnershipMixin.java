package com.kltyton.companioncall.mixin.entity;

import com.kltyton.companioncall.entity.ownership.ManualOwnership;
import com.kltyton.companioncall.entity.ownership.ManualOwnershipCarrier;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.HangingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Persists and synchronizes explicit recall bindings without changing native taming data. */
@Mixin(Entity.class)
public abstract class ManualOwnershipMixin implements ManualOwnershipCarrier {
    @Unique private static final EntityDataAccessor<String> companionCall$OWNER =
            SynchedEntityData.defineId(Entity.class, EntityDataSerializers.STRING);
    @Unique private String companionCall$ownerText;
    @Unique private UUID companionCall$owner;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void companionCall$defineOwner(CallbackInfo callback) {
        ((Entity) (Object) this).getEntityData().define(companionCall$OWNER, "");
    }

    @Override public UUID companionCall$manualOwner() {
        String raw = ((Entity) (Object) this).getEntityData().get(companionCall$OWNER);
        if (!raw.equals(companionCall$ownerText)) {
            companionCall$ownerText = raw;
            companionCall$owner = raw.isEmpty() ? null : UUID.fromString(raw);
        }
        return companionCall$owner;
    }

    @Override public void companionCall$manualOwner(UUID owner) {
        ((Entity) (Object) this).getEntityData().set(companionCall$OWNER, owner == null ? "" : owner.toString());
    }

    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void companionCall$saveOwner(CompoundTag tag, CallbackInfoReturnable<CompoundTag> callback) {
        UUID owner = companionCall$manualOwner();
        if (owner == null) tag.remove(ManualOwnership.OWNER_TAG);
        else tag.putUUID(ManualOwnership.OWNER_TAG, owner);
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void companionCall$loadOwner(CompoundTag tag, CallbackInfo callback) {
        companionCall$manualOwner(tag.hasUUID(ManualOwnership.OWNER_TAG) ? tag.getUUID(ManualOwnership.OWNER_TAG) : null);
        if ((Object) this instanceof HangingEntity entity && companionCall$manualOwner() != null) {
            var position = tag.getList("Pos", 6);
            if (position.size() == 3) entity.setPos(position.getDouble(0), position.getDouble(1), position.getDouble(2));
        }
    }

    @Inject(method = "onSyncedDataUpdated(Lnet/minecraft/network/syncher/EntityDataAccessor;)V", at = @At("RETURN"))
    private void companionCall$restoreNetworkPosition(EntityDataAccessor<?> key, CallbackInfo callback) {
        if (key == companionCall$OWNER && (Object) this instanceof HangingEntity entity
                && entity.level().isClientSide() && companionCall$manualOwner() != null) ManualOwnership.restoreNetworkPosition(entity);
    }
}
