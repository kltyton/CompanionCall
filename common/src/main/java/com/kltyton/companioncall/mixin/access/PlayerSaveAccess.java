package com.kltyton.companioncall.mixin.access;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Uses native player persistence for players carried by a migrating mount. */
@Mixin(PlayerList.class)
public interface PlayerSaveAccess {
    @Invoker("save") void companionCall$save(ServerPlayer player);
}
