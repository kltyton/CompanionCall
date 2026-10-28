package com.kltyton.companioncall.entity.navigation;

import com.kltyton.companioncall.api.TravelMode;
import com.kltyton.companioncall.platform.NativeVersion;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** Supplies shared-route intent to native animation controls without invoking another pathfinder. */
public final class RecallMotionIntent {
    private RecallMotionIntent() {}

    public static void prepare(Mob mob, Vec3 target, double multiplier, TravelMode mode) {
        double centerOffset = ((int) (mob.getBbWidth() + 1.0F)) * .5;
        var node = new Node(Mth.floor(target.x - centerOffset), Mth.floor(target.y), Mth.floor(target.z - centerOffset));
        NativeVersion.setPathMode(node, mode);
        var nodes = new ArrayList<Node>(1);
        nodes.add(node);
        mob.getNavigation().moveTo(new Path(nodes, BlockPos.containing(target), true), multiplier);
        mob.getMoveControl().setWantedPosition(target.x, target.y, target.z, multiplier);
    }

    /** Native navigation quantizes nodes; animation controls must face the shared motion instead. */
    public static void alignWithSharedMotion(Mob mob) {
        Vec3 motion = mob.getDeltaMovement();
        double length = motion.length();
        if (length < 1.0e-6) return;
        Vec3 direction = motion.scale(1 / length);
        if (motion.horizontalDistanceSqr() < 1.0e-8) {
            Vec3 facing = Vec3.directionFromRotation(0, mob.getYRot());
            direction = new Vec3(facing.x, direction.y, facing.z);
        }
        Vec3 target = mob.position().add(direction.scale(Math.max(2, length * 2)));
        var control = mob.getMoveControl();
        control.setWantedPosition(target.x, target.y, target.z, control.getSpeedModifier());
    }
}
