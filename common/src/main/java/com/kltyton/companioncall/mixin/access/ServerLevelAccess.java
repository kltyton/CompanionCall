package com.kltyton.companioncall.mixin.access;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the native entity persistence owner for local transaction barriers. */
@Mixin(ServerLevel.class)
public interface ServerLevelAccess {
    @Accessor("entityManager") PersistentEntitySectionManager<Entity> companionCall$entities();
}
