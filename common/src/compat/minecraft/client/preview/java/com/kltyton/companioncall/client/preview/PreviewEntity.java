package com.kltyton.companioncall.client.preview;

import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.storage.pet.PetRecord;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.Level;

/** A display-only entity never joins a level or runs entity ticks. */
final class PreviewEntity {
    static final float BODY_ROTATION = 145;
    private static final float PORTRAIT_ROTATION = 180;
    private static int nextPreviewId = -1;
    private PetRecord record;
    private Level level;
    private Entity entity;
    private long lastGameTime;
    private boolean failureLogged;

    void update(PetRecord next) {
        if (next == null) {
            close();
            return;
        }
        boolean changed = record == null || !record.id().equals(next.id())
                || !record.type().equals(next.type()) || record.sitting() != next.sitting()
                || !Objects.equals(record.appearance(), next.appearance())
                || level != Minecraft.getInstance().level;
        record = next;
        if (changed) rebuild();
    }

    Entity current(boolean portrait) {
        if (record == null) return null;
        if (level != Minecraft.getInstance().level) rebuild();
        if (entity == null) return null;
        long now = level.getGameTime();
        if (now > lastGameTime) entity.tickCount += (int) (now - lastGameTime);
        lastGameTime = now;
        float rotation = portrait ? PORTRAIT_ROTATION : BODY_ROTATION;
        if (entity.getYRot() != rotation) {
            entity.setYRot(rotation);
            entity.setYBodyRot(rotation);
            entity.setYHeadRot(rotation);
            entity.setOldPosAndRot();
            if (entity instanceof LivingEntity living) {
                living.yBodyRotO = rotation;
                living.yHeadRotO = rotation;
            }
        }
        return entity;
    }

    private void rebuild() {
        entity = null;
        level = Minecraft.getInstance().level;
        failureLogged = false;
        if (level == null || record.appearance() == null) {
            return;
        }
        try {
            entity = PreviewEntityLoader.create(record.type(), record.appearance(), level);
            // GUI instances must not share renderer instance IDs with server-spawned entities.
            entity.setId(nextPreviewId--);
            entity.setPos(0, 0, 0);
            entity.setYRot(BODY_ROTATION);
            entity.setXRot(0);
            entity.setYBodyRot(BODY_ROTATION);
            entity.setYHeadRot(BODY_ROTATION);
            entity.setOldPosAndRot();
            if (entity instanceof LivingEntity living) {
                living.yBodyRotO = BODY_ROTATION;
                living.yHeadRotO = BODY_ROTATION;
            }
            if (entity instanceof TamableAnimal tamable) tamable.setOrderedToSit(record.sitting());
            lastGameTime = level.getGameTime();
        } catch (CommandSyntaxException | RuntimeException error) {
            entity = null;
            failed(error);
        }
    }

    void failed(Exception error) {
        entity = null;
        if (failureLogged) return;
        failureLogged = true;
        CompanionCall.LOGGER.warn("Unable to display companion {} ({})", record.id(), record.type(), error);
    }

    void close() {
        entity = null;
        record = null;
        level = null;
        failureLogged = false;
    }
}
