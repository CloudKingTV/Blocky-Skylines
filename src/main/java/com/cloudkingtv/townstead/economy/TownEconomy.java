package com.cloudkingtv.townstead.economy;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.TownGrowth;
import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.sim.TownModel;
import com.cloudkingtv.townstead.zone.LandData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The town's books, server side. Every half minute a slice of the day's taxes comes in and a slice of upkeep and
 * loan payments goes out (scaled by the growth speed, like everything else), and the ledger records it. A town in
 * debt runs its services at half strength until the treasury is back above zero. Also the mayor's money actions:
 * taxes, budgets, loans, and emeralds in and out at the Town Hall.
 */
@EventBusSubscriber(modid = Townstead.MODID)
public final class TownEconomy {
    /** Ticks between settlements (a fortieth of a day). */
    public static final int SETTLE_INTERVAL = 600;
    /** One emerald, in the treasury's hundredths. */
    public static final long EMERALD = 100;
    /** What a new town starts with; it stays in the treasury as a reserve (it can't be withdrawn). */
    public static final long FOUNDING_PURSE = 25 * EMERALD;

    /** What the mayor may take out: the treasury beyond the reserve and what's still owed on loans. */
    public static long withdrawable(long treasury, Budget budget) {
        long owed = 0;
        for (Loan l : budget.loans) owed += l.owed();
        return Math.max(0, treasury - FOUNDING_PURSE - owed);
    }

    @SubscribeEvent
    static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % SETTLE_INTERVAL != 0) return;
        CityManager cities = CityManager.get(level.getServer());
        for (City city : List.copyOf(cities.all())) {
            if (city.dimension().equals(level.dimension())) settle(level, cities, city);
        }
    }

    /**
     * The town's model as it stands. Working it out takes a while in a big town (every trip routed), so it's kept
     * while the town's buildings, roads and plans are unchanged, for up to {@link #MODEL_TTL} ticks (taxes, budgets
     * and the treasury may lag that long).
     */
    public static TownModel model(ServerLevel level, City city) {
        List<Building> town = LandData.get(level).buildingsOf(city.id());
        RoadNetwork roads = RoadNetwork.get(level);
        long key = TownModel.fingerprint(town, roads, city.civics(), city.tiles().size());
        Cached c = MODELS.get(city.id());
        long now = level.getGameTime();
        if (c != null && c.key == key && now - c.time >= 0 && now - c.time < MODEL_TTL) return c.model;
        TownModel m = TownModel.of(city.id(), town, roads, city.budget(), city.treasury(), city.tiles(), city.townHall(), city.civics(),
                city.stock(), city.storehouses().size());
        MODELS.put(city.id(), new Cached(m, key, now));
        return m;
    }

    private static final int MODEL_TTL = 100;
    private record Cached(TownModel model, long key, long time) {}
    private static final Map<java.util.UUID, Cached> MODELS = new java.util.concurrent.ConcurrentHashMap<>();



    private static void settle(ServerLevel level, CityManager cities, City city) {
        TownModel town = model(level, city);
        com.cloudkingtv.townstead.progress.Milestones.check(level, city, town);
        Finance.Report day = Finance.daily(town);
        double share = TownGrowth.speed() * SETTLE_INTERVAL / 24000.0;
        // The industry's slice of the day, against the stock as it is now; its surplus is sold.
        long exported = produce(level, cities, city, com.cloudkingtv.townstead.industry.Industry.day(town,
                com.cloudkingtv.townstead.industry.Industry.counts(city.stock()), city.storehouses().size(), share));
        long income = Math.round((day.income() - day.exports()) * share) + exported;
        long upkeep = Math.round((day.expenses() - day.loans()) * share);
        boolean wasBroke = city.treasury() < 0;
        List<Loan> next = new ArrayList<>(), paidOff = new ArrayList<>();
        long loanPaid = 0;
        for (Loan l : city.budget().loans) {
            long pay = Math.min(l.owed(), Math.max(1, Math.round(l.daily() * share)));
            loanPaid += pay;
            Loan after = l.pay(pay);
            if (after.owed() > 0) next.add(after);
            else paidOff.add(l);
        }
        long expenses = upkeep + loanPaid;
        cities.settle(level.getServer(), city, income - expenses, budget -> {
            budget.loans.clear();
            budget.loans.addAll(next);
            budget.record(new Budget.Entry(income, expenses, city.treasury()));
        });
        for (Loan l : paidOff) tell(level, city, Component.translatable("economy.townstead.loan_repaid", Component.translatable("economy.townstead.loan." + l.offer().key())), true);
        boolean broke = city.treasury() < 0;
        if (broke && !wasBroke) {
            tell(level, city, Component.translatable("economy.townstead.broke"), false);
            com.cloudkingtv.townstead.progress.Chirps.post(level, city, "broke", city.townHall(), true, city.name());
        }
        if (!broke && wasBroke) {
            tell(level, city, Component.translatable("economy.townstead.solvent"), true);
            com.cloudkingtv.townstead.progress.Chirps.post(level, city, "solvent", city.townHall(), true, city.name());
        }
    }

    /** Fractions of a unit (and of a cent) the industry has made but not yet delivered, per town and material. */
    private static final Map<java.util.UUID, double[]> CARRY = new java.util.concurrent.ConcurrentHashMap<>();

    /** Puts a slice of the industry's work into the storehouse; returns what the surplus sold for. */
    private static long produce(ServerLevel level, CityManager cities, City city, com.cloudkingtv.townstead.industry.Industry.Day work) {
        if (!work.any()) return 0;
        com.cloudkingtv.townstead.road.Material[] mats = com.cloudkingtv.townstead.road.Material.values();
        double[] carry = CARRY.computeIfAbsent(city.id(), k -> new double[mats.length + 1]);
        int[] whole = new int[mats.length];
        boolean any = false;
        for (com.cloudkingtv.townstead.road.Material m : mats) {
            double d = work.net(m) + carry[m.ordinal()];
            whole[m.ordinal()] = (int) d; // toward zero, the rest carried
            carry[m.ordinal()] = d - whole[m.ordinal()];
            any |= whole[m.ordinal()] != 0;
        }
        double cents = work.exportValue() + carry[mats.length];
        long sold = (long) Math.floor(cents);
        carry[mats.length] = cents - sold;
        if (any) cities.editStock(level.getServer(), city, stock -> {
            for (com.cloudkingtv.townstead.road.Material m : mats) {
                int n = whole[m.ordinal()];
                if (n > 0) com.cloudkingtv.townstead.city.Stock.add(stock, m.item(), n);
                else if (n < 0) com.cloudkingtv.townstead.city.Stock.consume(stock, Map.of(m, Math.min(-n, com.cloudkingtv.townstead.city.Stock.count(stock, m))));
            }
        });
        return sold;
    }

    // ---------------------------------------------------------------- the mayor's actions

    /**
     * Buys materials in for the storehouse at the import price (three times what they sell for), from the treasury
     * and never into debt: a town that can't make something can still get it.
     */
    public static void buy(ServerPlayer player, City city, int material, int count) {
        if (material < 0 || material >= com.cloudkingtv.townstead.road.Material.values().length || count <= 0) return;
        com.cloudkingtv.townstead.road.Material m = com.cloudkingtv.townstead.road.Material.values()[material];
        int n = Math.min(count, 64 * 27);
        long cost = (long) n * m.importPrice();
        if (city.treasury() - cost < 0) {
            RoadEditor.notify(player, Component.translatable("economy.townstead.cant_afford_import", n, m.displayName(), Budget.format(cost)), false);
            return;
        }
        CityManager.get(player.level().getServer()).addTreasury(player.level().getServer(), city, -cost);
        CityManager.get(player.level().getServer()).editStock(player.level().getServer(), city, stock -> com.cloudkingtv.townstead.city.Stock.add(stock, m.item(), n));
        player.level().playSound(null, player.blockPosition(), SoundEvents.VILLAGER_TRADE, SoundSource.NEUTRAL, 0.8f, 1.0f);
        RoadEditor.notify(player, Component.translatable("economy.townstead.imported", n, m.displayName(), Budget.format(cost)), true);
    }

    public static void setTax(ServerPlayer player, City city, int kind, int rate) {
        if (kind < 0 || kind >= Budget.TAX_KINDS) return;
        CityManager.get(player.level().getServer()).editBudget(player.level().getServer(), city,
                b -> b.taxes[kind] = Mth.clamp(rate, Budget.MIN_TAX, Budget.MAX_TAX));
    }

    public static void setBudget(ServerPlayer player, City city, int department, int percent) {
        if (department < 0 || department >= ServiceType.Department.values().length) return;
        CityManager.get(player.level().getServer()).editBudget(player.level().getServer(), city,
                b -> b.budgets[department] = Mth.clamp(percent, Budget.MIN_BUDGET, Budget.MAX_BUDGET));
    }

    public static void takeLoan(ServerPlayer player, City city, int offer) {
        if (offer < 0 || offer >= Loan.Offer.values().length) return;
        Loan.Offer o = Loan.Offer.values()[offer];
        if (city.budget().hasLoan(o)) return;
        CityManager cities = CityManager.get(player.level().getServer());
        cities.settle(player.level().getServer(), city, o.amount, b -> b.loans.add(o.take()));
        RoadEditor.notify(player, Component.translatable("economy.townstead.loan_taken", Budget.format(o.amount)), true);
    }

    /** Pays off what's left of a loan at once, if the treasury can. */
    public static void repayLoan(ServerPlayer player, City city, int offer) {
        if (offer < 0 || offer >= Loan.Offer.values().length) return;
        Loan.Offer o = Loan.Offer.values()[offer];
        Loan loan = city.budget().loans.stream().filter(l -> l.offer() == o).findFirst().orElse(null);
        if (loan == null) return;
        if (city.treasury() < loan.owed()) {
            RoadEditor.notify(player, Component.translatable("economy.townstead.cant_repay", Budget.format(loan.owed())), false);
            return;
        }
        CityManager.get(player.level().getServer()).settle(player.level().getServer(), city, -loan.owed(), b -> b.loans.remove(loan));
        RoadEditor.notify(player, Component.translatable("economy.townstead.loan_repaid", Component.translatable("economy.townstead.loan." + o.key())), true);
    }

    /** Emeralds (and emerald blocks) from the player's hand into the treasury. Anyone may give to a town. */
    public static boolean deposit(ServerPlayer player, City city, ItemStack stack, BlockPos at) {
        long each = stack.is(Items.EMERALD) ? EMERALD : stack.is(Items.EMERALD_BLOCK) ? EMERALD * 9 : 0;
        if (each == 0 || !stack.isComponentsPatchEmpty()) return false;
        int n = stack.getCount();
        CityManager.get(player.level().getServer()).addTreasury(player.level().getServer(), city, each * n);
        stack.setCount(0);
        player.level().playSound(null, at, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1f, 1.2f);
        player.sendOverlayMessage(Component.translatable("economy.townstead.deposited", Budget.format(each * n), Budget.format(city.treasury())));
        return true;
    }

    /** Every emerald and emerald block the player carries, into the treasury. */
    public static void depositAll(ServerPlayer player, City city, BlockPos at) {
        boolean any = false;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.is(Items.EMERALD) || stack.is(Items.EMERALD_BLOCK)) any |= deposit(player, city, stack, at);
        }
        if (!any) player.sendOverlayMessage(Component.translatable("economy.townstead.no_emeralds"));
    }

    /** The town whose Town Hall is at pos, if the player stands near it. */
    public static java.util.Optional<City> townHall(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        if (!level.getBlockState(pos).is(com.cloudkingtv.townstead.registry.TownsteadRegistry.TOWN_HALL.get())) return java.util.Optional.empty();
        if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 64) return java.util.Optional.empty();
        return CityManager.get(level.getServer()).byTownHall(level, pos);
    }

    /** Whole emeralds out of the treasury, for the mayor only, never below zero. */
    public static void withdraw(ServerPlayer player, City city, int emeralds) {
        if (!city.owner().equals(player.getUUID())) return;
        int n = (int) Math.min(Math.max(0, emeralds), withdrawable(city.treasury(), city.budget()) / EMERALD);
        if (n <= 0) {
            player.sendOverlayMessage(Component.translatable("economy.townstead.nothing_to_withdraw"));
            return;
        }
        CityManager.get(player.level().getServer()).addTreasury(player.level().getServer(), city, -n * EMERALD);
        int left = n;
        while (left > 0) {
            int take = Math.min(64, left);
            player.getInventory().placeItemBackInInventory(new ItemStack(Items.EMERALD, take), Prediction.SERVER_ONLY);
            left -= take;
        }
        player.sendOverlayMessage(Component.translatable("economy.townstead.withdrew", n, Budget.format(city.treasury())));
    }

    private static void tell(ServerLevel level, City city, Component message, boolean ok) {
        for (ServerPlayer mayor : city.mayorsOnline(level.getServer())) RoadEditor.notify(mayor, message, ok);
    }

    private TownEconomy() {}
}
