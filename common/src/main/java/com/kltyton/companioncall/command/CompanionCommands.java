package com.kltyton.companioncall.command;

import com.kltyton.companioncall.CompanionCall;
import com.kltyton.companioncall.api.CompanionCallApi;
import com.kltyton.companioncall.platform.NativeVersion;
import com.kltyton.companioncall.server.CompanionServer;
import com.kltyton.companioncall.storage.pet.PetRecord;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class CompanionCommands {
    private static final int PAGE_SIZE = 10;
    private static final SimpleCommandExceptionType NOT_OWNED = error("not_owned");
    private static final SimpleCommandExceptionType BUSY = error("busy");
    private static final SimpleCommandExceptionType DEAD = error("dead");
    private static final SimpleCommandExceptionType UNSUPPORTED = error("owner_unsupported");
    private static final SimpleCommandExceptionType REJECTED = error("owner_rejected");
    private static final SimpleCommandExceptionType OWNER_CONTRACT = error("owner_contract");

    private CompanionCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("companioncall")
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> list(context.getSource(), IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("select")
                        .then(Commands.argument("pet", UuidArgument.uuid())
                                .suggests(CompanionCommands::suggestOwn)
                                .executes(CompanionCommands::select)))
                .then(Commands.literal("recall")
                        .executes(context -> recall(context, null, false))
                        .then(Commands.argument("pet", UuidArgument.uuid())
                                .suggests(CompanionCommands::suggestOwn)
                                .executes(context -> recall(context, UuidArgument.getUuid(context, "pet"), false))))
                .then(Commands.literal("force-recall").requires(NativeVersion::canManage)
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("pet", UuidArgument.uuid())
                                        .suggests((context, builder) -> suggest(EntityArgument.getPlayer(context, "player"), builder))
                                        .executes(context -> recall(context, UuidArgument.getUuid(context, "pet"), true)))))
                .then(Commands.literal("set-owner").requires(NativeVersion::canManage)
                        .then(Commands.argument("entity", EntityArgument.entity())
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(CompanionCommands::setOwner)))));
    }

    private static SimpleCommandExceptionType error(String suffix) {
        return new SimpleCommandExceptionType(Component.translatable("companion_call.command." + suffix));
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("companion_call.command.help"), false);
        if (NativeVersion.canManage(source)) {
            source.sendSuccess(() -> Component.translatable("companion_call.command.help_admin"), false);
        }
        return 1;
    }

    private static int list(CommandSourceStack source, int page) throws CommandSyntaxException {
        var player = source.getPlayerOrException();
        var runtime = CompanionCall.server(source.getServer());
        var pets = runtime.index.owned(player.getUUID());
        if (pets.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("companion_call.command.empty"), false);
            return 0;
        }
        int pages = (pets.size() - 1) / PAGE_SIZE + 1;
        if (page > pages) {
            throw new SimpleCommandExceptionType(Component.translatable("companion_call.command.page_invalid", pages)).create();
        }
        UUID selected = runtime.index.selected(player.getUUID());
        source.sendSuccess(() -> Component.translatable("companion_call.command.list_header", pets.size(), page, pages), false);
        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, pets.size());
        for (var pet : pets.subList(start, end)) {
            String marker = pet.id().equals(selected) ? "*" : "-";
            source.sendSuccess(() -> Component.translatable("companion_call.command.list_entry", marker, pet.name(), pet.id().toString()), false);
        }
        return end - start;
    }

    private static int select(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var player = source.getPlayerOrException();
        var runtime = CompanionCall.server(source.getServer());
        UUID id = UuidArgument.getUuid(context, "pet");
        var pet = requireOwned(runtime, player, id);
        runtime.index.select(player.getUUID(), id);
        runtime.persist();
        runtime.sync(player);
        source.sendSuccess(() -> Component.translatable("companion_call.command.selected", pet.name(), id.toString()), false);
        return 1;
    }

    private static int recall(CommandContext<CommandSourceStack> context, UUID requested, boolean force) throws CommandSyntaxException {
        var source = context.getSource();
        var owner = force ? EntityArgument.getPlayer(context, "player") : source.getPlayerOrException();
        var runtime = CompanionCall.server(source.getServer());
        UUID id = requested == null ? runtime.index.selected(owner.getUUID()) : requested;
        if (id == null) throw NOT_OWNED.create();
        var pet = requireOwned(runtime, owner, id);
        if (runtime.transfers.locked(id)) throw BUSY.create();
        if (!runtime.recalls.request(owner, requested, force)) {
            source.sendFailure(Component.translatable("companion_call.command.request_failed",
                    Component.translatable("companion_call.status." + runtime.recalls.status(owner.getUUID()))));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(force ? "companion_call.command.force_queued" : "companion_call.command.recall_queued",
                pet.name(), owner.getDisplayName()), force);
        return 1;
    }

    private static PetRecord requireOwned(CompanionServer runtime, ServerPlayer owner, UUID id) throws CommandSyntaxException {
        var loaded = runtime.findLoaded(id);
        if (loaded != null) runtime.observe(loaded);
        var pet = runtime.index.get(id);
        if (pet == null || !pet.valid() || !owner.getUUID().equals(pet.owner())) throw NOT_OWNED.create();
        return pet;
    }

    private static int setOwner(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        var source = context.getSource();
        var entity = EntityArgument.getEntity(context, "entity");
        var owner = EntityArgument.getPlayer(context, "player");
        var runtime = CompanionCall.server(source.getServer());
        if (!entity.isAlive()) throw DEAD.create();
        if (runtime.transfers.locked(entity.getUUID())) throw BUSY.create();
        var adapter = CompanionCallApi.adapter(entity);
        if (adapter == null) throw UNSUPPORTED.create();
        UUID previous = adapter.owner(entity);
        if (owner.getUUID().equals(previous)) {
            source.sendSuccess(() -> Component.translatable("companion_call.command.owner_unchanged", entity.getDisplayName(), owner.getDisplayName()), false);
            return 1;
        }
        switch (adapter.setOwner(entity, owner)) {
            case UNSUPPORTED -> throw UNSUPPORTED.create();
            case REJECTED -> throw REJECTED.create();
            case SUCCESS -> {
                if (!owner.getUUID().equals(adapter.owner(entity))) {
                    runtime.observe(entity);
                    runtime.persist();
                    throw OWNER_CONTRACT.create();
                }
            }
        }
        runtime.recalls.cancel(entity.getUUID(), "not_owned");
        CompanionCallApi.closeViewers(entity);
        runtime.observe(entity);
        runtime.persist();
        if (previous != null) {
            var oldOwner = source.getServer().getPlayerList().getPlayer(previous);
            if (oldOwner != null) runtime.sync(oldOwner);
        }
        runtime.sync(owner);
        source.sendSuccess(() -> Component.translatable("companion_call.command.owner_changed", entity.getDisplayName(), owner.getDisplayName()), true);
        return 1;
    }

    private static CompletableFuture<Suggestions> suggestOwn(CommandContext<CommandSourceStack> context,
                                                             SuggestionsBuilder builder) throws CommandSyntaxException {
        if (!(context.getSource().getEntity() instanceof ServerPlayer player)) return builder.buildFuture();
        return suggest(player, builder);
    }

    private static CompletableFuture<Suggestions> suggest(ServerPlayer player, SuggestionsBuilder builder) {
        var runtime = CompanionCall.server(((net.minecraft.server.level.ServerLevel) player.level()).getServer());
        String remaining = builder.getRemainingLowerCase();
        for (var pet : runtime.index.owned(player.getUUID())) {
            String id = pet.id().toString();
            if (id.startsWith(remaining)) builder.suggest(id);
        }
        return builder.buildFuture();
    }
}
