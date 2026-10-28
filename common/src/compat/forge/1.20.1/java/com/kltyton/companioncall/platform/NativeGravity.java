package com.kltyton.companioncall.platform;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.ForgeMod;

final class NativeGravity {
    private NativeGravity() {}
    static double value(LivingEntity entity) { return entity.getAttributeValue(ForgeMod.ENTITY_GRAVITY.get()); }
}
