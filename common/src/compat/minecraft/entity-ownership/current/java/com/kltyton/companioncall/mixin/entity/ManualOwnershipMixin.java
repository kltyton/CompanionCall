package com.kltyton.companioncall.mixin.entity;

import com.kltyton.companioncall.entity.ownership.ManualOwnership;
import com.kltyton.companioncall.entity.ownership.ManualOwnershipCarrier;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Persists and synchronizes explicit recall bindings without changing native taming data. */
@Mixin(Entity.class)
public abstract class ManualOwnershipMixin implements ManualOwnershipCarrier {
    @Unique private static final EntityDataAccessor<String> companionCall$OWNER =
            SynchedEntityData.defineId(Entity.class, EntityDataSerializers.STRING);
    @Unique private String companionCall$ownerText;
    @Unique private UUID companionCall$owner;

    @ModifyExpressionValue(method = "<init>", at = @At(value = "NEW",
            target = "net/minecraft/network/syncher/SynchedEntityData$Builder"))
    private SynchedEntityData.Builder companionCall$defineOwner(SynchedEntityData.Builder builder) {
        return builder.define(companionCall$OWNER, "");
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
    private void companionCall$saveOwner(ValueOutput output, CallbackInfo callback) {
        UUID owner = companionCall$manualOwner();
        if (owner != null) output.store(ManualOwnership.OWNER_TAG, UUIDUtil.CODEC, owner);
    }

    @Inject(method = "load", at = @At("RETURN"))
    private void companionCall$loadOwner(ValueInput input, CallbackInfo callback) {
        companionCall$manualOwner(input.read(ManualOwnership.OWNER_TAG, UUIDUtil.CODEC).orElse(null));
        if ((Object) this instanceof BlockAttachedEntity entity && companionCall$manualOwner() != null) {
            input.read("Pos", Vec3.CODEC).ifPresent(entity::setPos);
        }
    }

    @Inject(method = "onSyncedDataUpdated(Lnet/minecraft/network/syncher/EntityDataAccessor;)V", at = @At("RETURN"))
    private void companionCall$restoreNetworkPosition(EntityDataAccessor<?> key, CallbackInfo callback) {
        if (key == companionCall$OWNER && (Object) this instanceof BlockAttachedEntity entity
                && entity.level().isClientSide() && companionCall$manualOwner() != null) ManualOwnership.restoreNetworkPosition(entity);
    }
}
