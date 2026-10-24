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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import com.cloudkingtv.townstead.zone.LandData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;
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
 * run; {@code /townstead ignite}, {@code transfer <player> [town]} and {@code perf} for operators.
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
                .then(Commands.literal("transfer").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("player", EntityArgument.player()).executes(TownsteadCommands::transfer)
                                .then(Commands.argument("town", StringArgumentType.greedyString())
                                        .suggests((ctx, b) -> {
                                            CityManager.get(ctx.getSource().getServer()).all().forEach(c -> b.suggest(c.name()));
                                            return b.buildFuture();
                                        })
                                        .executes(TownsteadCommands::transfer))))
                .then(Commands.literal("perf").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)).executes(TownsteadCommands::perf))
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

    /**
     * Hands a town to another player (operators: for towns whose mayor is gone): the town named, or the one the
     * operator stands in. The server console has to name it.
     */
    private static int transfer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer to = EntityArgument.getPlayer(ctx, "player");
        CityManager cities = CityManager.get(ctx.getSource().getServer());
        Optional<City> found;
        if (ctx.getNodes().stream().anyMatch(n -> n.getNode().getName().equals("town"))) {
            String name = StringArgumentType.getString(ctx, "town");
            found = cities.all().stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst();
        } else {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            found = cities.at(player.level(), player.blockPosition());
        }
        City city = found.orElseThrow(() -> new SimpleCommandExceptionType(Component.translatable("message.townstead.not_in_town")).create());
        if (CityManager.get(ctx.getSource().getServer()).ownedBy(to.getUUID()).isPresent()) {
            throw new SimpleCommandExceptionType(Component.translatable("message.townstead.already_mayor", to.getName())).create();
        }
        CityManager.get(ctx.getSource().getServer()).setOwner(ctx.getSource().getServer(), city, to.getUUID(), to.getName().getString());
        ctx.getSource().sendSuccess(() -> Component.translatable("message.townstead.transferred", city.name(), to.getName()), true);
        to.sendSystemMessage(Component.translatable("message.townstead.claimed", city.name()));
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

    /** Where the server's time went over the last ten seconds, town by town. */
    private static int perf(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        var last = TownProfiler.lastWindow();
        if (TownProfiler.windowTicks() == 0) {
            src.sendSuccess(() -> Component.translatable("perf.townstead.warming_up", TownProfiler.WINDOW / 20), false);
            return 0;
        }
        double ticks = TownProfiler.windowTicks();
        long all = 0;
        for (TownProfiler.Usage u : last.values()) all += u.total;
        double mod = all / ticks / 1e6, tick = TownProfiler.serverTick() / 1e6;
        src.sendSuccess(() -> Component.translatable("perf.townstead.header", ms(mod), ms(tick), TownProfiler.WINDOW / 20,
                ms(TownsteadConfig.TOWN_TICK_BUDGET.get()), com.cloudkingtv.townstead.network.PayloadGuard.dropped()), false);
        var server = src.getServer();
        List<City> towns = new ArrayList<>(CityManager.get(server).all());
        towns.sort(Comparator.comparingLong((City c) -> last.containsKey(c.id()) ? last.get(c.id()).total : 0).reversed());
        for (City city : towns) {
            TownProfiler.Usage u = last.get(city.id());
            var level = server.getLevel(city.dimension());
            int people = 0, buildings = 0;
            if (level != null) for (Building b : LandData.get(level).buildingsOf(city.id())) {
                buildings++;
                if (b.working()) people += com.cloudkingtv.townstead.building.Residents.people(b);
            }
            int p = people, n = buildings;
            if (u == null) {
                src.sendSuccess(() -> Component.translatable("perf.townstead.idle", city.name(), p, n), false);
                continue;
            }
            src.sendSuccess(() -> Component.translatable("perf.townstead.town", city.name(), p, n, ms(u.total / ticks / 1e6), ms(u.peak / 1e6),
                    u.overBudget, u.deferred), false);
            src.sendSuccess(() -> sections(u, ticks), false);
            if (u.models > 0) src.sendSuccess(() -> Component.translatable("perf.townstead.models", u.models, ms(u.modelNanos / 1e6 / u.models))
                    .withStyle(ChatFormatting.GRAY), false);
        }
        TownProfiler.Usage shared = last.get(TownProfiler.SERVER);
        if (shared != null) {
            src.sendSuccess(() -> Component.translatable("perf.townstead.shared", ms(shared.total / ticks / 1e6), ms(shared.peak / 1e6)), false);
            src.sendSuccess(() -> sections(shared, ticks), false);
        }
        return 1;
    }

    /** "model 0.41 · build 0.20 · ...", biggest first, in milliseconds a tick. */
    private static Component sections(TownProfiler.Usage u, double ticks) {
        List<Map.Entry<TownProfiler.Section, Long>> parts = new ArrayList<>(u.sections.entrySet());
        parts.sort(Map.Entry.<TownProfiler.Section, Long>comparingByValue().reversed());
        MutableComponent line = Component.literal("   ").withStyle(ChatFormatting.GRAY);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) line.append(" · ");
            line.append(Component.translatable("perf.townstead.section." + parts.get(i).getKey().name().toLowerCase(Locale.ROOT)))
                    .append(" " + ms(parts.get(i).getValue() / ticks / 1e6));
        }
        return line;
    }

    private static String ms(double millis) {
        return String.format(Locale.ROOT, millis < 10 ? "%.2f" : "%.1f", millis);
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
