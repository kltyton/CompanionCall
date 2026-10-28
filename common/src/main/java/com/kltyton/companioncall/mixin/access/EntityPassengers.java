package com.kltyton.companioncall.mixin.access;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Uses each entity's native passenger capacity and restrictions for manual registrations. */
@Mixin(Entity.class)
public interface EntityPassengers {
    @Invoker("canAddPassenger") boolean companionCall$canAddPassenger(Entity passenger);
}
