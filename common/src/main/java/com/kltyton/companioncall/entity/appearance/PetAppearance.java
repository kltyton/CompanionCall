package com.kltyton.companioncall.entity.appearance;

import com.kltyton.companioncall.api.PetAdapter;
import com.kltyton.companioncall.platform.NativeVersion;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/** Render data only; entity identity, movement, passengers and pet inventories are not exported. */
public final class PetAppearance {
    private static final String[] VANILLA = {"Age", "Owner", "Tame", "Sitting", "Variant", "variant",
            "CollarColor", "ArmorItems", "HandItems", "ArmorItem", "body_armor_item", "SaddleItem",
            "DecorItem", "ChestedHorse", "equipment"};
    private static final String[] EXCLUDED = {"id", "UUID", "UUIDMost", "UUIDLeast", "Pos", "Motion",
            "Rotation", "Passengers", "RootVehicle", "Dimension", "Inventory", "Items", "Brain",
            "CompanionCallTransfer", "CompanionCallTransferRole", "companion_call:owner"};

    private PetAppearance() {}

    public static CompoundTag vanilla(Entity entity) {
        var source = NativeVersion.previewSource(entity);
        var result = new CompoundTag();
        for (String key : VANILLA) {
            var value = source.get(key);
            if (value != null) result.put(key, value.copy());
        }
        return result;
    }

    public static String capture(Entity entity, PetAdapter adapter) {
        var result = adapter.previewData(entity).copy();
        for (String key : EXCLUDED) result.remove(key);
        return result.toString();
    }
}
