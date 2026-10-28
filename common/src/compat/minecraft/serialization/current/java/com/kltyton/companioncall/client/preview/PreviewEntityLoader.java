package com.kltyton.companioncall.client.preview;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.storage.TagValueInput;

final class PreviewEntityLoader {
    private PreviewEntityLoader() {}

    static Entity create(String typeId, String appearance, Level level) throws CommandSyntaxException {
        CompoundTag tag = TagParser.parseCompoundFully(appearance);
        for (String key : new String[]{"id", "UUID", "UUIDMost", "UUIDLeast", "Pos", "Motion", "Rotation",
                "Passengers", "RootVehicle", "Dimension", "PortalCooldown", "FallDistance", "Fire", "Air",
                "OnGround", "HurtTime", "HurtByTimestamp", "DeathTime", "Invulnerable"}) tag.remove(key);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.tryParse(typeId))
                .orElseThrow(() -> new IllegalArgumentException("Missing companion entity type: " + typeId));
        return EntityType.create(type, TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag),
                level, EntitySpawnReason.LOAD)
                .orElseThrow(() -> new IllegalStateException("Entity type cannot create a preview: " + typeId));
    }
}
