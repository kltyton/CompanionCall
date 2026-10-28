package com.kltyton.companioncall.entity.ownership;

import java.util.UUID;
import net.minecraft.world.entity.Entity;

public final class ManualOwnership {
    public static final String OWNER_TAG = "companion_call:owner";
    private ManualOwnership() {}

    public static UUID owner(Entity entity) {
        return entity instanceof ManualOwnershipCarrier carrier ? carrier.companionCall$manualOwner() : null;
    }

    public static void set(Entity entity, UUID owner) {
        if (entity instanceof ManualOwnershipCarrier carrier) carrier.companionCall$manualOwner(owner);
    }

    /** Ownership arrives after the spawn position, which an attached entity may round to its wall grid. */
    public static void restoreNetworkPosition(Entity entity) {
        var exact = entity.getPositionCodec().decode(0, 0, 0);
        var box = entity.getBoundingBox().move(exact.subtract(entity.position()));
        entity.setPosRaw(exact.x, exact.y, exact.z);
        entity.setBoundingBox(box);
    }
}
