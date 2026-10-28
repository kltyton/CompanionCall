package com.kltyton.companioncall.mixin.transfer;

import com.kltyton.companioncall.storage.transfer.StorageHooks;
import com.kltyton.companioncall.storage.transfer.TransferMark;
import net.minecraft.world.entity.Entity;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the transaction marker inside the original native entity serialization. */
@Mixin(Entity.class)
public abstract class EntityMarkMixin implements TransferMark {
    @Unique private String companionCall$nonce = "";
    @Unique private String companionCall$role = "";
    @Unique private boolean companionCall$loading;
    @Override public String companionCall$nonce() { return companionCall$nonce; }
    @Override public String companionCall$role() { return companionCall$role; }
    @Override public boolean companionCall$loading() { return companionCall$loading; }
    @Override public void companionCall$mark(String nonce, String role) {
        companionCall$nonce = nonce;
        companionCall$role = role;
    }
    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void companionCall$save(CompoundTag tag, CallbackInfoReturnable<CompoundTag> callback) {
        if (!companionCall$nonce.isEmpty()) {
            callback.getReturnValue().putString(StorageHooks.NONCE, companionCall$nonce);
            callback.getReturnValue().putString(StorageHooks.ROLE, companionCall$role);
        }
    }
    @Inject(method = "load", at = @At("HEAD"))
    private void companionCall$read(CompoundTag input, CallbackInfo callback) {
        companionCall$loading = true;
        companionCall$nonce = input.getString(StorageHooks.NONCE);
        companionCall$role = input.getString(StorageHooks.ROLE);
    }
    @Inject(method = "load", at = @At("RETURN"))
    private void companionCall$readFinished(CompoundTag input, CallbackInfo callback) {
        companionCall$loading = false;
    }
}
