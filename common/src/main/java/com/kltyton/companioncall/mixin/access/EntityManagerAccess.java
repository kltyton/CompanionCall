package com.kltyton.companioncall.mixin.access;

import java.util.function.Consumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityPersistentStorage;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Saves only the requested loaded entity chunk, including every other entity in it. */
@Mixin(PersistentEntitySectionManager.class)
public interface EntityManagerAccess {
    @Accessor("permanentStorage") EntityPersistentStorage<?> companionCall$storage();
    @Invoker("storeChunkSections") boolean companionCall$save(long chunk, Consumer<EntityAccess> visitor);
    @Invoker("ensureChunkQueuedForLoad") void companionCall$queueLoad(long chunk);
}
