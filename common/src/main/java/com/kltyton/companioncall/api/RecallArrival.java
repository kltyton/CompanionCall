package com.kltyton.companioncall.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Physical boarding position shared by vanilla navigation and third-party mount adapters. */
public final class RecallArrival {
    public static final double VERTICAL_TOLERANCE = .5;
    private RecallArrival() {}

    public static Vec3 seatPosition(Entity mount, ServerPlayer owner) {
        return mount.position().add(CompanionCallApi.adapter(mount).boardingOffset(mount, owner));
    }

    public static double horizontalRadius(Entity mount, ServerPlayer owner, boolean grounded) {
        return owner.getBbWidth() * .5 + (grounded ? mount.getBbWidth() * .5 + .001 : 0);
    }

    public static double minimumRelativeY(Entity mount, Vec3 seat, boolean grounded) {
        return grounded ? mount.getBoundingBox().minY - seat.y - .05 : -.05;
    }

    public static double maximumRelativeY(Entity mount, Vec3 seat, boolean grounded) {
        return grounded ? mount.getBoundingBox().maxY - seat.y + VERTICAL_TOLERANCE : VERTICAL_TOLERANCE;
    }

    public static boolean sideReady(Entity mount, ServerPlayer owner) {
        if (!owner.onGround()) return true;
        var playerBox = owner.getBoundingBox();
        var mountBox = mount.getBoundingBox();
        boolean outside = mount.getX() <= playerBox.minX || mount.getX() >= playerBox.maxX
                || mount.getZ() <= playerBox.minZ || mount.getZ() >= playerBox.maxZ;
        return outside && mountBox.minY <= playerBox.minY + .05;
    }

    public static boolean isAtBoardingPosition(Entity mount, ServerPlayer owner) {
        if (mount.level() != owner.level()) return false;
        if (!sideReady(mount, owner)) return false;
        Vec3 seat = seatPosition(mount, owner);
        boolean grounded = owner.onGround();
        double dx = (grounded ? mount.getX() : seat.x) - owner.getX();
        double dz = (grounded ? mount.getZ() : seat.z) - owner.getZ();
        double radius = horizontalRadius(mount, owner, grounded);
        double relativeY = owner.getY() - seat.y;
        boolean vertical = relativeY >= minimumRelativeY(mount, seat, grounded)
                && relativeY <= maximumRelativeY(mount, seat, grounded);
        var mountBox = mount.getBoundingBox();
        var ownerBox = owner.getBoundingBox();
        boolean bodyContact = ownerBox.maxX + .001 >= mountBox.minX && ownerBox.minX - .001 <= mountBox.maxX
                && ownerBox.maxZ + .001 >= mountBox.minZ && ownerBox.minZ - .001 <= mountBox.maxZ;
        boolean horizontal = bodyContact || !grounded && dx * dx + dz * dz <= radius * radius;
        return horizontal && vertical;
    }
}
