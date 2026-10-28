package com.kltyton.companioncall.entity.ownership;

import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.api.Approach;
import com.kltyton.companioncall.api.OwnerChangeResult;
import com.kltyton.companioncall.api.PetAdapter;
import com.kltyton.companioncall.api.RecallPursuit;
import com.kltyton.companioncall.api.TravelMode;
import com.kltyton.companioncall.entity.navigation.EntityRecallNavigation;
import com.kltyton.companioncall.mixin.access.EntityPassengers;
import com.kltyton.companioncall.platform.NativeVersion;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.TamableAnimal;

/** Explicit player bindings provide recall authority without inventing a native tameable owner. */
public final class BoundEntityAdapter implements PetAdapter {
    public static final BoundEntityAdapter INSTANCE = new BoundEntityAdapter();

    private BoundEntityAdapter() {}

    @Override public boolean matches(Entity entity) { return true; }

    @Override public UUID owner(Entity entity) {
        UUID nativeOwner = NativeVersion.owner(entity);
        if (nativeOwner != null) return nativeOwner;
        UUID explicitOwner = ManualOwnership.owner(entity);
        if (explicitOwner != null) return explicitOwner;
        if (!(entity.level() instanceof ServerLevel level)) return null;
        var runtime = CompanionCall.activeServer(level.getServer());
        if (runtime == null) return null;
        var record = runtime.index.get(entity.getUUID());
        if (record != null && !record.type().equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())) return null;
        return runtime.index.bindingOwner(entity.getUUID());
    }

    @Override public OwnerChangeResult setOwner(Entity entity, ServerPlayer owner) {
        if (NativeVersion.owner(entity) != null) return NativeVersion.setOwner(entity, owner);
        var runtime = CompanionCall.server(((ServerLevel) entity.level()).getServer());
        runtime.index.bind(entity.getUUID(), owner.getUUID());
        ManualOwnership.set(entity, owner.getUUID());
        return OwnerChangeResult.SUCCESS;
    }

    @Override public Set<TravelMode> travelModes(Entity entity) {
        return EnumSet.allOf(TravelMode.class);
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
        return approach(entity, owner, RecallPursuit.legacy(entity, owner, speed));
    }

    @Override public Approach approach(Entity entity, ServerPlayer owner, RecallPursuit pursuit) {
        return EntityRecallNavigation.approach(entity, owner, pursuit);
    }

    @Override public void stopApproach(Entity entity) { EntityRecallNavigation.stop(entity); }

    @Override public boolean canRide(Entity entity, ServerPlayer owner) {
        return ((EntityPassengers) entity).companionCall$canAddPassenger(owner);
    }

    @Override public void beforeTransfer(Entity entity, ServerLevel destination) { stopApproach(entity); }
    @Override public void afterTransfer(Entity entity, ServerLevel origin) { stopApproach(entity); }
}
