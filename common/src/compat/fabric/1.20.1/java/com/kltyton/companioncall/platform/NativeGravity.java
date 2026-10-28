package com.kltyton.companioncall.platform;

import net.minecraft.world.entity.LivingEntity;

final class NativeGravity {
    private NativeGravity() {}
    static double value(LivingEntity entity) { return LivingEntity.DEFAULT_BASE_GRAVITY; }
}
