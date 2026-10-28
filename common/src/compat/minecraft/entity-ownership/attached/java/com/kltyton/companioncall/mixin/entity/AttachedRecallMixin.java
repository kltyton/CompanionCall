package com.kltyton.companioncall.mixin.entity;

import com.kltyton.companioncall.entity.ownership.ManualOwnership;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Gives bound block-attached entities self motion while preserving damage and external forces. */
@Mixin(BlockAttachedEntity.class)
public abstract class AttachedRecallMixin extends Entity {
    protected AttachedRecallMixin(EntityType<?> type, Level level) { super(type, level); }

    @WrapMethod(method = "move")
    private void companionCall$selfMotion(MoverType type, Vec3 motion, Operation<Void> original) {
        if (type == MoverType.SELF && ManualOwnership.owner(this) != null) super.move(type, motion);
        else original.call(type, motion);
    }

    @WrapMethod(method = "setPos(DDD)V")
    private void companionCall$continuousPosition(double x, double y, double z, Operation<Void> original) {
        original.call(x, y, z);
        if (ManualOwnership.owner(this) != null) {
            var box = getBoundingBox().move(x - getX(), y - getY(), z - getZ());
            setPosRaw(x, y, z);
            setBoundingBox(box);
        }
    }

    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/decoration/BlockAttachedEntity;survives()Z"))
    private boolean companionCall$recallSupport(BlockAttachedEntity entity, Operation<Boolean> original) {
        boolean attached = original.call(entity);
        return attached || ManualOwnership.owner(entity) != null
                && !entity.level().getBlockCollisions(entity, entity.getBoundingBox()).iterator().hasNext();
    }
}
