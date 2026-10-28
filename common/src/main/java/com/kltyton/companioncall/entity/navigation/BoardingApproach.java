package com.kltyton.companioncall.entity.navigation;

import com.kltyton.companioncall.api.CompanionCallApi;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/** Server authorization for a mount to move under its rider during the final safe approach. */
public final class BoardingApproach {
    private BoardingApproach() {}

    public static void authorize(Entity pet, Player player) {
        if (!(player instanceof ServerPlayer owner) || !pet.isAlive() || !owner.isAlive() || owner.isSpectator()
                || pet.level() != owner.level() || pet.isPassenger()
                || owner.isPassenger() || pet.distanceToSqr(owner) > 9) {
            clear(pet);
            return;
        }
        var adapter = CompanionCallApi.adapter(pet);
        if (adapter == null || !owner.getUUID().equals(adapter.owner(pet)) || !adapter.canRide(pet, owner)) {
            clear(pet);
            return;
        }
        ((BoardingApproachCarrier) pet).companionCall$boardingOwner(owner.getStringUUID());
    }

    public static void clear(Entity pet) {
        ((BoardingApproachCarrier) pet).companionCall$boardingOwner("");
    }

    public static boolean matches(Entity first, Entity second) {
        if (first instanceof Player player) return matchesMount(second, player);
        return second instanceof Player player && matchesMount(first, player);
    }

    private static boolean matchesMount(Entity pet, Player player) {
        return pet.isAlive() && !pet.isRemoved() && player.isAlive() && !player.isRemoved() && !player.isSpectator()
                && pet.level() == player.level() && !pet.isPassenger()
                && !player.isPassenger() && pet.distanceToSqr(player) <= 9
                && player.getStringUUID().equals(((BoardingApproachCarrier) pet).companionCall$boardingOwner());
    }
}
