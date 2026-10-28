package com.kltyton.companioncall.mixin.access;

import com.kltyton.companioncall.storage.transfer.PetMenuAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.inventory.HorseInventoryMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

/** Identifies only viewers of the particular mount inventory being transferred. */
@Mixin(HorseInventoryMenu.class)
public abstract class HorseMenuAccess implements PetMenuAccess {
    @Shadow @Final private AbstractHorse horse;
    @Override public Entity companionCall$pet() { return horse; }
}
