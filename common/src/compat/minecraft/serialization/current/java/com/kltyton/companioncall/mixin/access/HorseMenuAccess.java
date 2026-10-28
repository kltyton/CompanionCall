package com.kltyton.companioncall.mixin.access;

import com.kltyton.companioncall.storage.transfer.PetMenuAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.inventory.AbstractMountInventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

/** Identifies only viewers of the particular mount inventory being transferred. */
@Mixin(AbstractMountInventoryMenu.class)
public abstract class HorseMenuAccess implements PetMenuAccess {
    @Shadow @Final protected LivingEntity mount;
    @Override public Entity companionCall$pet() { return mount; }
}
