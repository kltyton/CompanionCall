package com.kltyton.companioncall.mixin.access;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Allows a temporary recall goal to participate in the existing goal arbitration. */
@Mixin(Mob.class)
public interface MobGoals {
    @Accessor("goalSelector") GoalSelector companionCall$goals();
}
