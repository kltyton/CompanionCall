package com.kltyton.companioncall.loader.neoforge.entity;

import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.entity.glow.RecallGlow;
import com.kltyton.companioncall.entity.ownership.ManualOwnership;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.AttachmentSyncHandler;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;

/** Native entity attachments synchronize ownership and transient recall state to tracking clients. */
@EventBusSubscriber(modid = CompanionCall.MOD_ID)
public final class RecallEntityAttachments {
    public static final AttachmentType<Integer> RECALL_COLOR = AttachmentType.builder(() -> RecallGlow.NONE)
            .sync(ByteBufCodecs.VAR_INT).build();
    public static final AttachmentType<String> BOARDING_OWNER = AttachmentType.builder(() -> "")
            .sync(ByteBufCodecs.stringUtf8(36)).build();
    public static final AttachmentType<Optional<UUID>> MANUAL_OWNER = AttachmentType.<Optional<UUID>>builder(Optional::empty)
            .serialize(UUIDUtil.CODEC.optionalFieldOf("owner"))
            .sync(new AttachmentSyncHandler<Optional<UUID>>() {
                @Override public void write(RegistryFriendlyByteBuf buffer, Optional<UUID> value, boolean initialSync) {
                    ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC).encode(buffer, value);
                }

                @Override public Optional<UUID> read(IAttachmentHolder holder, RegistryFriendlyByteBuf buffer,
                                                     Optional<UUID> previous) {
                    Optional<UUID> value = ByteBufCodecs.optional(UUIDUtil.STREAM_CODEC).decode(buffer);
                    if (value.isPresent() && holder instanceof BlockAttachedEntity entity) {
                        ManualOwnership.restoreNetworkPosition(entity);
                    }
                    return value;
                }
            }).build();

    private RecallEntityAttachments() {}

    @SubscribeEvent public static void register(RegisterEvent event) {
        event.register(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, helper -> {
            helper.register(Identifier.fromNamespaceAndPath(CompanionCall.MOD_ID, "recall_color"), RECALL_COLOR);
            helper.register(Identifier.fromNamespaceAndPath(CompanionCall.MOD_ID, "boarding_owner"), BOARDING_OWNER);
            helper.register(Identifier.fromNamespaceAndPath(CompanionCall.MOD_ID, "manual_owner"), MANUAL_OWNER);
        });
    }
}
