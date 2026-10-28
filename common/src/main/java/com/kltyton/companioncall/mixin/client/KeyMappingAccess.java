package com.kltyton.companioncall.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads the user's actual binding while a Screen has released the normal key-down flags. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccess {
    @Accessor("key") InputConstants.Key companionCall$key();
}
