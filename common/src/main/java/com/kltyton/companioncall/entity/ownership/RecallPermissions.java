package com.kltyton.companioncall.entity.ownership;

import java.util.UUID;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

public final class RecallPermissions {
    private RecallPermissions() {}

    public static boolean carriesOtherPlayer(Entity entity, UUID owner) {
        for (var passenger : entity.getPassengers()) {
            if (passenger instanceof Player && !owner.equals(passenger.getUUID())) return true;
            if (carriesOtherPlayer(passenger, owner)) return true;
        }
        return false;
    }

    public static void detachForRecall(Entity entity, UUID owner) {
        if (entity.isPassenger()) entity.stopRiding();
        detachOtherPlayers(entity, owner);
    }

    private static void detachOtherPlayers(Entity entity, UUID owner) {
        for (var passenger : List.copyOf(entity.getPassengers())) {
            if (passenger instanceof Player && !owner.equals(passenger.getUUID())) passenger.stopRiding();
            else detachOtherPlayers(passenger, owner);
        }
    }
}
