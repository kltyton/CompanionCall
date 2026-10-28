package com.kltyton.companioncall.navigation;

import com.kltyton.companioncall.api.TravelMode;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Immutable movement envelope used by the custom navigator.
 *
 * <p>The collision shape is the entity's real AABB stored as six offsets relative to
 * {@link Entity#position()}. It is deliberately not reduced to a square width; non-centred,
 * non-square and non-zero-minY boxes are preserved for all collision, sweep and passage work.
 * Multiple travel modes are allowed for amphibious/flying companions, while a single-mode profile
 * remains available for Eden's explicit AIR/LAND phases.</p>
 */
public final class NavigationProfile {
    private static final double DEFAULT_JUMP_HEIGHT = 1.25;
    private static final double DEFAULT_DROP_HEIGHT = 3.0;
    private static final double BOX_EPSILON = 1.0E-4;

    private final Set<TravelMode> modes;
    private final double minX;
    private final double minY;
    private final double minZ;
    private final double maxX;
    private final double maxY;
    private final double maxZ;
    private final double maxUpStep;
    private final boolean canJump;
    private final double maxJumpHeight;
    private final boolean canDrop;
    private final double maxDropHeight;
    private final boolean boxOverridden;
    private final boolean capabilitiesOverridden;

    private NavigationProfile(Set<TravelMode> modes, double minX, double minY, double minZ,
                              double maxX, double maxY, double maxZ, double maxUpStep,
                              boolean canJump, double maxJumpHeight, boolean canDrop,
                              double maxDropHeight, boolean boxOverridden,
                              boolean capabilitiesOverridden) {
        if (modes == null || modes.isEmpty()) {
            throw new IllegalArgumentException("At least one travel mode is required");
        }
        if (!(maxX >= minX) || !(maxY >= minY) || !(maxZ >= minZ)) {
            throw new IllegalArgumentException("Collision box dimensions must not be inverted");
        }
        if (!(maxUpStep >= 0.0) || !(maxJumpHeight >= 0.0) || !(maxDropHeight >= 0.0)) {
            throw new IllegalArgumentException("Movement capabilities must not be negative");
        }
        this.modes = Collections.unmodifiableSet(EnumSet.copyOf(modes));
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.maxUpStep = maxUpStep;
        this.canJump = canJump;
        this.maxJumpHeight = maxJumpHeight;
        this.canDrop = canDrop;
        this.maxDropHeight = maxDropHeight;
        this.boxOverridden = boxOverridden;
        this.capabilitiesOverridden = capabilitiesOverridden;
    }

    public static NavigationProfile from(Entity entity) {
        return from(entity, TravelMode.LAND);
    }

    public static NavigationProfile from(Entity entity, TravelMode mode) {
        if (mode == null) throw new IllegalArgumentException("Travel mode is required");
        return from(entity, EnumSet.of(mode));
    }

    /** Existing amphibious/flying adapters can pass their complete set of supported modes. */
    public static NavigationProfile from(Entity entity, Set<TravelMode> modes) {
        if (entity == null) throw new IllegalArgumentException("Entity is required");
        if (modes == null || modes.isEmpty()) {
            throw new IllegalArgumentException("At least one travel mode is required");
        }
        EnumSet<TravelMode> copy = EnumSet.copyOf(modes);
        AABB box = entity.getBoundingBox();
        Vec3 position = entity.position();
        boolean land = copy.contains(TravelMode.LAND);
        return new NavigationProfile(copy, box.minX - position.x, box.minY - position.y,
                box.minZ - position.z, box.maxX - position.x, box.maxY - position.y,
                box.maxZ - position.z, entity.maxUpStep(), land,
                land ? DEFAULT_JUMP_HEIGHT : 0.0, land, land ? DEFAULT_DROP_HEIGHT : 0.0,
                false, false);
    }

    /**
     * Explicit single-mode phase (for example Eden takeoff/landing) while keeping the exact box.
     */
    public NavigationProfile withMode(TravelMode mode) {
        if (mode == null) return this;
        if (modes.size() == 1 && modes.contains(mode)) return this;
        boolean land = mode == TravelMode.LAND;
        return new NavigationProfile(EnumSet.of(mode), minX, minY, minZ, maxX, maxY, maxZ,
                maxUpStep, capabilitiesOverridden ? canJump : land,
                capabilitiesOverridden ? maxJumpHeight : (land ? DEFAULT_JUMP_HEIGHT : 0.0),
                capabilitiesOverridden ? canDrop : land,
                capabilitiesOverridden ? maxDropHeight : (land ? DEFAULT_DROP_HEIGHT : 0.0),
                boxOverridden, capabilitiesOverridden);
    }

    /** Replaces the shape with a box whose coordinates are relative to the entity position. */
    public NavigationProfile withCollisionBox(AABB localBox) {
        return new NavigationProfile(modes, localBox.minX, localBox.minY, localBox.minZ,
                localBox.maxX, localBox.maxY, localBox.maxZ, maxUpStep, canJump, maxJumpHeight,
                canDrop, maxDropHeight, true, capabilitiesOverridden);
    }

    /** Convenience overload that converts an absolute entity box into local offsets. */
    public NavigationProfile withCollisionBox(AABB worldBox, Vec3 origin) {
        return withCollisionBox(new AABB(worldBox.minX - origin.x, worldBox.minY - origin.y,
                worldBox.minZ - origin.z, worldBox.maxX - origin.x, worldBox.maxY - origin.y,
                worldBox.maxZ - origin.z));
    }

    public NavigationProfile withCapabilities(double maxUpStep, boolean canJump,
                                              double maxJumpHeight, boolean canDrop,
                                              double maxDropHeight) {
        return new NavigationProfile(modes, minX, minY, minZ, maxX, maxY, maxZ, maxUpStep,
                canJump, maxJumpHeight, canDrop, maxDropHeight, boxOverridden, true);
    }

    NavigationProfile adaptTo(Entity entity) {
        if (matches(entity) && (capabilitiesOverridden
                || Math.abs(maxUpStep - entity.maxUpStep()) <= 1.0E-9)) {
            return this;
        }
        NavigationProfile actual = from(entity, modes);
        if (boxOverridden) actual = actual.withCollisionBox(localBox());
        if (capabilitiesOverridden) {
            actual = actual.withCapabilities(maxUpStep, canJump, maxJumpHeight, canDrop,
                    maxDropHeight);
        }
        return sameAs(actual) ? this : actual;
    }

    boolean sameAs(NavigationProfile other) {
        if (other == null) return false;
        return modes.equals(other.modes)
                && Math.abs(minX - other.minX) < BOX_EPSILON
                && Math.abs(minY - other.minY) < BOX_EPSILON
                && Math.abs(minZ - other.minZ) < BOX_EPSILON
                && Math.abs(maxX - other.maxX) < BOX_EPSILON
                && Math.abs(maxY - other.maxY) < BOX_EPSILON
                && Math.abs(maxZ - other.maxZ) < BOX_EPSILON
                && Math.abs(maxUpStep - other.maxUpStep) < 1.0E-9
                && canJump == other.canJump
                && Math.abs(maxJumpHeight - other.maxJumpHeight) < 1.0E-9
                && canDrop == other.canDrop
                && Math.abs(maxDropHeight - other.maxDropHeight) < 1.0E-9;
    }

    boolean matches(Entity entity) {
        if (boxOverridden) return true;
        AABB box = entity.getBoundingBox();
        Vec3 position = entity.position();
        return Math.abs(box.minX - position.x - minX) < BOX_EPSILON
                && Math.abs(box.minY - position.y - minY) < BOX_EPSILON
                && Math.abs(box.minZ - position.z - minZ) < BOX_EPSILON
                && Math.abs(box.maxX - position.x - maxX) < BOX_EPSILON
                && Math.abs(box.maxY - position.y - maxY) < BOX_EPSILON
                && Math.abs(box.maxZ - position.z - maxZ) < BOX_EPSILON;
    }

    /** Primary mode retained for compatibility; use {@link #allows(TravelMode)} for routing. */
    public TravelMode mode() { return primaryMode(); }
    public TravelMode primaryMode() {
        if (modes.contains(TravelMode.LAND)) return TravelMode.LAND;
        return modes.iterator().next();
    }
    public Set<TravelMode> modes() { return modes; }
    public boolean allows(TravelMode mode) { return mode != null && modes.contains(mode); }
    public double maxUpStep() { return maxUpStep; }
    public boolean canJump() { return canJump; }
    public double maxJumpHeight() { return maxJumpHeight; }
    public boolean canDrop() { return canDrop; }
    public double maxDropHeight() { return maxDropHeight; }

    public AABB boxAt(double x, double feetY, double z) {
        return new AABB(x + minX, feetY + minY, z + minZ,
                x + maxX, feetY + maxY, z + maxZ);
    }

    public AABB boxAt(Vec3 feet) {
        return boxAt(feet.x, feet.y, feet.z);
    }

    AABB localBox() {
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    double localMinX() { return minX; }
    double localMinY() { return minY; }
    double localMinZ() { return minZ; }
    double localMaxX() { return maxX; }
    double localMaxY() { return maxY; }
    double localMaxZ() { return maxZ; }
}
