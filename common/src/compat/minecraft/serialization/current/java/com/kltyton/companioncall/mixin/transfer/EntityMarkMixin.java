package com.kltyton.companioncall.mixin.transfer;

import com.kltyton.companioncall.storage.transfer.StorageHooks;
import com.kltyton.companioncall.storage.transfer.TransferMark;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


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
    private void companionCall$save(ValueOutput output, CallbackInfo callback) {
        if (!companionCall$nonce.isEmpty()) {
            output.putString(StorageHooks.NONCE, companionCall$nonce);
            output.putString(StorageHooks.ROLE, companionCall$role);
        }
    }
    @Inject(method = "load", at = @At("HEAD"))
    private void companionCall$read(ValueInput input, CallbackInfo callback) {
        companionCall$loading = true;
        companionCall$nonce = input.getStringOr(StorageHooks.NONCE, "");
        companionCall$role = input.getStringOr(StorageHooks.ROLE, "");
    }
    @Inject(method = "load", at = @At("RETURN"))
    private void companionCall$readFinished(ValueInput input, CallbackInfo callback) {
        companionCall$loading = false;
    }
}
