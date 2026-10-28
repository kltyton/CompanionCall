package com.kltyton.companioncall.entity.ownership;

import com.kltyton.companioncall.api.Approach;
import com.kltyton.companioncall.api.PetAdapter;
import com.kltyton.companioncall.api.RecallPursuit;
import com.kltyton.companioncall.api.TravelMode;
import com.kltyton.companioncall.entity.navigation.RecallNavigation;
import com.kltyton.companioncall.platform.NativeVersion;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.WaterBoundPathNavigation;

public final class VanillaPetAdapter implements PetAdapter {
    @Override public boolean matches(Entity entity) {
        return entity instanceof Mob && (entity instanceof OwnableEntity || NativeVersion.isHorse(entity));
    }

    @Override public UUID owner(Entity entity) { return NativeVersion.owner(entity); }

    @Override public Set<TravelMode> travelModes(Entity entity) {
        var navigation = ((Mob) entity).getNavigation();
        if (navigation instanceof FlyingPathNavigation) return EnumSet.of(TravelMode.LAND, TravelMode.AIR);
        if (navigation instanceof AmphibiousPathNavigation) return EnumSet.of(TravelMode.LAND, TravelMode.WATER);
        if (navigation instanceof WaterBoundPathNavigation) return EnumSet.of(TravelMode.WATER);
        return EnumSet.of(TravelMode.LAND);
    }

    @Override public boolean isSitting(Entity entity) {
        return entity instanceof TamableAnimal pet && pet.isOrderedToSit();
    }

    @Override public void stand(Entity entity) {
        if (entity instanceof TamableAnimal pet) {
            pet.setOrderedToSit(false);
            pet.setInSittingPose(false);
        }
    }

    @Override public Approach approach(Entity entity, ServerPlayer owner, double speed) {
        return RecallNavigation.approach((Mob) entity, owner, speed);
    }

    @Override public Approach approach(Entity entity, ServerPlayer owner, RecallPursuit pursuit) {
        return RecallNavigation.approach((Mob) entity, owner, pursuit);
    }

    @Override public void stopApproach(Entity entity) { RecallNavigation.stop((Mob) entity); }
    @Override public boolean canRide(Entity entity, ServerPlayer owner) { return NativeVersion.canRide(entity, owner); }
    @Override public void beforeTransfer(Entity entity, ServerLevel destination) {
        stopApproach(entity);
    }
    @Override public void afterTransfer(Entity entity, ServerLevel origin) { ((Mob) entity).getNavigation().stop(); }
}
