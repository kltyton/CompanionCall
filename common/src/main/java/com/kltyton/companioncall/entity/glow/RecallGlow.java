package com.kltyton.companioncall.entity.glow;

import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/** Temporary recall outlines use native entity synchronization without changing teams or glow tags. */
public final class RecallGlow {
    public static final int NONE = -1;

    private RecallGlow() {}

    public static int color(UUID owner) {
        long bits = owner.getMostSignificantBits() ^ Long.rotateLeft(owner.getLeastSignificantBits(), 21);
        bits = (bits ^ bits >>> 30) * 0xbf58476d1ce4e5b9L;
        bits = (bits ^ bits >>> 27) * 0x94d049bb133111ebL;
        bits ^= bits >>> 31;
        float hue = (bits & 0xffffffL) / 16777216.0F;
        float saturation = 0.6F + ((bits >>> 24) & 255L) / 255.0F * 0.3F;
        float brightness = 0.85F + ((bits >>> 32) & 255L) / 255.0F * 0.15F;
        return Mth.hsvToRgb(hue, saturation, brightness);
    }

    public static void show(Entity entity, int color) {
        ((RecallGlowCarrier) entity).companionCall$recallColor(color);
    }

    public static void clear(Entity entity) {
        ((RecallGlowCarrier) entity).companionCall$recallColor(NONE);
    }
}
