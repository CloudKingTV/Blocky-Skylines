package com.cloudkingtv.townstead;

import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.city.TownActions;
import com.cloudkingtv.townstead.disaster.Fires;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /townstead info}, {@code rename <name>} and {@code comayor [add <player> | remove <name>]} for the town you
 * run; {@code /townstead ignite} for operators.
 */
@EventBusSubscriber(modid = Townstead.MODID)
public final class TownsteadCommands {
    @SubscribeEvent
    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(Townstead.MODID)
                .then(Commands.literal("info").executes(TownsteadCommands::info))
                .then(Commands.literal("comayor")
                        .then(Commands.literal("add").then(Commands.argument("player", EntityArgument.player())
                                .executes(TownsteadCommands::addCoMayor)))
                        .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    var p = ctx.getSource().getPlayer();
                                    if (p != null) CityManager.get(ctx.getSource().getServer()).ownedBy(p.getUUID())
                                            .ifPresent(c -> c.coMayors().values().forEach(b::suggest));
                                    return b.buildFuture();
                                })
                                .executes(TownsteadCommands::removeCoMayor)))
                        .executes(TownsteadCommands::listCoMayors))
                .then(Commands.literal("ignite").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(TownsteadCommands::ignite))
                .then(Commands.literal("rename")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(ctx -> TownActions.rename(ctx.getSource().getPlayerOrException(), myTown(ctx),
                                        StringArgumentType.getString(ctx, "name")) ? 1 : 0))));
    }

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        City city = myTown(ctx);
        ctx.getSource().sendSuccess(() -> Component.translatable("message.townstead.info", city.name(),
                city.tiles().size(), city.treasury(), city.townHall().toShortString()), false);
        return 1;
    }

    /** The mayor shares the running of the town with another player. */
    private static int addCoMayor(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        City city = myTown(ctx);
        ServerPlayer mayor = ctx.getSource().getPlayerOrException(), other = EntityArgument.getPlayer(ctx, "player");
        if (other.getUUID().equals(city.owner()) || city.coMayors().containsKey(other.getUUID())) {
            throw new SimpleCommandExceptionType(Component.translatable("comayor.townstead.already", other.getName())).create();
        }
        CityManager.get(ctx.getSource().getServer()).addCoMayor(ctx.getSource().getServer(), city, other.getUUID(), other.getName().getString());
        ctx.getSource().sendSuccess(() -> Component.translatable("comayor.townstead.added", other.getName(), city.name()), false);
        other.sendSystemMessage(Component.translatable("comayor.townstead.welcome", mayor.getName(), city.name()));
        return 1;
    }

    private static int removeCoMayor(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        City city = myTown(ctx);
        String name = StringArgumentType.getString(ctx, "name");
        java.util.UUID id = city.coMayors().entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(name))
                .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
        if (id == null || !CityManager.get(ctx.getSource().getServer()).removeCoMayor(ctx.getSource().getServer(), city, id)) {
            throw new SimpleCommandExceptionType(Component.translatable("comayor.townstead.not_found", name)).create();
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("comayor.townstead.removed", name, city.name()), false);
        return 1;
    }

    private static int listCoMayors(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        City city = CityManager.get(ctx.getSource().getServer()).runBy(player.getUUID()).orElseThrow(
                () -> new SimpleCommandExceptionType(Component.translatable("message.townstead.planner.no_town")).create());
        ctx.getSource().sendSuccess(() -> Component.translatable("comayor.townstead.list", city.name(), city.ownerName(),
                city.coMayors().isEmpty() ? Component.translatable("comayor.townstead.none") : Component.literal(String.join(", ", city.coMayors().values()))), false);
        return 1;
    }

    /** Sets the nearest building of the town you stand in alight (for testing the fire brigade). */
    private static int ignite(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        City city = CityManager.get(ctx.getSource().getServer()).at(player.level(), player.blockPosition()).orElse(null);
        Building b = city == null ? null : Fires.nearest(player.level(), city, player.blockPosition());
        if (b == null || !Fires.ignite(player.level(), city, b)) {
            throw new SimpleCommandExceptionType(Component.translatable("fire.townstead.nothing")).create();
        }
        return 1;
    }

    private static City myTown(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<City> city = CityManager.get(ctx.getSource().getServer()).ownedBy(player.getUUID());
        if (city.isEmpty()) {
            throw new SimpleCommandExceptionType(Component.translatable("message.townstead.planner.no_town")).create();
        }
        return city.get();
    }

    private TownsteadCommands() {}
}
