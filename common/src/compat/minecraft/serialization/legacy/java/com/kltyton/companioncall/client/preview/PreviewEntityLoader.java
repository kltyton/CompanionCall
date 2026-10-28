package com.kltyton.companioncall.client.preview;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

final class PreviewEntityLoader {
    private PreviewEntityLoader() {}

    static Entity create(String typeId, String appearance, Level level) throws CommandSyntaxException {
        CompoundTag tag = TagParser.parseTag(appearance);
        for (String key : new String[]{"id", "UUID", "UUIDMost", "UUIDLeast", "Pos", "Motion", "Rotation",
                "Passengers", "RootVehicle", "Dimension", "PortalCooldown", "FallDistance", "Fire", "Air",
                "OnGround", "HurtTime", "HurtByTimestamp", "DeathTime", "Invulnerable"}) tag.remove(key);
        EntityType<?> type = EntityType.byString(typeId)
                .orElseThrow(() -> new IllegalArgumentException("Missing companion entity type: " + typeId));
        Entity entity = type.create(level);
        if (entity == null) throw new IllegalStateException("Entity type cannot create a preview: " + typeId);
        entity.load(tag);
        return entity;
    }
}
