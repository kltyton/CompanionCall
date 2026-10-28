package com.kltyton.companioncall.mixin.transfer;

import com.kltyton.companioncall.storage.transfer.StorageHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents carried players from changing inventory or vehicle position before commit. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerTransactionMixin {
    @Shadow public ServerPlayer player;
    @Inject(method = {"handleContainerClick", "handleContainerButtonClick", "handlePlayerAction",
            "handleUseItemOn", "handleUseItem", "handlePlayerCommand", "handleSetCreativeModeSlot",
            "handleSetCarriedItem", "handleMoveVehicle", "handleMovePlayer", "handlePlayerInput"},
            at = @At("HEAD"), cancellable = true)
    private void companionCall$holdInput(CallbackInfo callback) {
        if (StorageHooks.quarantined(player)) callback.cancel();
    }
}
