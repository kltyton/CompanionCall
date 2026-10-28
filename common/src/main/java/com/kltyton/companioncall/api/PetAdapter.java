package com.kltyton.companioncall.api;

import java.util.Set;
import java.util.UUID;
import com.kltyton.companioncall.entity.appearance.PetAppearance;
import com.kltyton.companioncall.platform.NativeVersion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Native ownership and locomotion contract for a recallable entity. */
public interface PetAdapter {
    boolean matches(Entity entity);

    /** Returns the current owner's persistent UUID, or null when unowned. */
    UUID owner(Entity entity);

    /** Writes real server-side ownership. Private ownership models override this operation. */
    default OwnerChangeResult setOwner(Entity entity, ServerPlayer owner) {
        return NativeVersion.setOwner(entity, owner);
    }

    Set<TravelMode> travelModes(Entity entity);

    boolean isSitting(Entity entity);

    void stand(Entity entity);

    /** Uses native movement. Rideable mounts keep approaching until RecallArrival's boarding position. */
    Approach approach(Entity entity, ServerPlayer owner, double speed);

    /** Consumes a predictive interception plan; legacy adapters retain their native speed contract. */
    default Approach approach(Entity entity, ServerPlayer owner, RecallPursuit pursuit) {
        return approach(entity, owner, pursuit.legacyMultiplier(entity));
    }

    void stopApproach(Entity entity);

    /** Includes age, equipment, permissions, and any custom riding restrictions. */
    boolean canRide(Entity entity, ServerPlayer owner);

    /** Actual rider feet offset, including custom seats and the passenger's vehicle attachment. */
    default Vec3 boardingOffset(Entity entity, ServerPlayer owner) {
        return NativeVersion.riderPosition(entity, owner).subtract(entity.position());
    }

    /** Pure visual NBT for an isolated client preview; never a world entity restoration source. */
    default CompoundTag previewData(Entity entity) { return PetAppearance.vanilla(entity); }

    /** Called before native migration, after the durable transfer intent exists. */
    void beforeTransfer(Entity entity, ServerLevel destination);

    /** Called with the native migration result, which may be a new Java instance. */
    void afterTransfer(Entity entity, ServerLevel origin);
}
