package com.cloudkingtv.townstead.industry;

import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.city.Stock;
import com.cloudkingtv.townstead.road.Material;
import com.cloudkingtv.townstead.sim.TownModel;
import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * What the town's industry makes, closing the loop between growth and the storehouse. Specialised industry is
 * <em>raw</em>: farms grow hay and wool, lumber yards fell logs, quarries cut stone. Ordinary workshops are
 * <em>processors</em>, each following one trade that turns raw materials into finished ones (planks, stone bricks,
 * glass, bricks, lanterns, workstations). Every working building runs a number of batches a day from its staff and
 * level; processors only run the batches the storehouse can feed. The storehouse has room for so much of each
 * material, and what's made beyond that is sold for the treasury.
 *
 * <p>Worked out from the shared {@link TownModel} plus the stock, so the planner shows what the server applies.
 */
public final class Industry {
    /** An industrial building's line of work. */
    public enum Trade {
        FARM("farm", Items.WHEAT, true),
        LUMBER("lumber", Items.OAK_LOG, true),
        QUARRY("quarry", Items.COBBLESTONE, true),
        CARPENTER("carpenter", Items.OAK_PLANKS, false),
        MASON("mason", Items.STONE_BRICKS, false),
        GLASSWORKS("glassworks", Items.GLASS, false),
        BRICKWORKS("brickworks", Items.BRICKS, false),
        SMITHY("smithy", Items.LANTERN, false),
        COOPER("cooper", Items.BARREL, false);

        private final String key;
        private final Item icon;
        /** Makes raw materials from nothing (true) or works up what's in the storehouse (false). */
        public final boolean raw;

        Trade(String key, Item icon, boolean raw) {
            this.key = key;
            this.icon = icon;
            this.raw = raw;
        }

        public String key() { return key; }
        public ItemStack icon() { return new ItemStack(icon); }
        public Component displayName() { return Component.translatable("trade.townstead." + key); }
    }

    /** The trades an ordinary workshop may follow, picked by its seed. */
    private static final Trade[] PROCESSORS = {Trade.CARPENTER, Trade.MASON, Trade.GLASSWORKS, Trade.BRICKWORKS, Trade.SMITHY, Trade.COOPER};

    /** Batches each worker runs a day, at level 1. */
    public static final int BATCHES_PER_WORKER = 10;
    private static final double[] LEVEL = {1.0, 1.5, 2.0};
    /** Room for each material: a base in the Town Hall's cellars, and more with every Town Storehouse. */
    public static final int BASE_CAPACITY = 512, PER_STOREHOUSE = 1024;

    /** One batch: what it takes from the storehouse and what it puts back. */
    public record Recipe(Map<Material, Integer> in, Map<Material, Integer> out) {}

    public static @Nullable Trade of(Building b) {
        if (b.zone != ZoneType.INDUSTRIAL) return null;
        return switch (b.spec) {
            case "farming" -> Trade.FARM;
            case "forestry" -> Trade.LUMBER;
            case "quarry" -> Trade.QUARRY;
            default -> PROCESSORS[(int) Math.floorMod(b.seed >>> 11, (long) PROCESSORS.length)];
        };
    }

    public static Recipe recipe(Building b) {
        Trade t = of(b);
        return t == null ? new Recipe(Map.of(), Map.of()) : recipe(t, b.level, b.style);
    }

    /** A trade's batch at a level, in a village style (the desert quarries sandstone, the savanna fires terracotta). */
    public static Recipe recipe(Trade t, int level, String style) {
        Map<Material, Integer> in = new EnumMap<>(Material.class), out = new EnumMap<>(Material.class);
        switch (t) {
            case FARM -> { out.put(Material.HAY, 2); out.put(Material.WOOL, 1); }
            case LUMBER -> {
                out.put(Material.LOGS, 3);
                out.put(Material.LEAVES, 1);
                if (level >= 2) out.put(Material.PLANKS, 2); // the sawmill
            }
            case QUARRY -> {
                if ("desert".equals(style)) out.put(Material.SANDSTONE, 3);
                else out.put(Material.COBBLESTONE, 3);
                out.put(Material.DIRT, 1);
                if ("savanna".equals(style)) out.put(Material.TERRACOTTA, 1);
                if (level >= 2) out.put(Material.STONE_BRICKS, 1); // the stone-cutting shed
            }
            case CARPENTER -> { in.put(Material.LOGS, 1); out.put(Material.PLANKS, 3); out.put(Material.FENCES, 1); }
            case MASON -> { in.put(Material.COBBLESTONE, 2); out.put(Material.STONE_BRICKS, 2); }
            case GLASSWORKS -> { in.put(Material.LOGS, 1); out.put(Material.GLASS, 3); }
            case BRICKWORKS -> {
                in.put(Material.DIRT, 2);
                in.put(Material.LOGS, 1);
                out.put(Material.BRICKS, 3);
                if ("savanna".equals(style) || "desert".equals(style)) out.put(Material.TERRACOTTA, 1);
            }
            case SMITHY -> { in.put(Material.COBBLESTONE, 1); in.put(Material.LOGS, 1); out.put(Material.LANTERNS, 1); }
            case COOPER -> { in.put(Material.PLANKS, 2); out.put(Material.WORKSTATIONS, 1); }
        }
        return new Recipe(in, out);
    }

    /** The trades that can make a material (at any level, in any style). */
    public static List<Trade> makers(Material mat) {
        List<Trade> out = new ArrayList<>();
        for (Trade t : Trade.values()) {
            for (String style : new String[] {"plains", "desert", "savanna"}) {
                if (recipe(t, 3, style).out().containsKey(mat)) { out.add(t); break; }
            }
        }
        return out;
    }

    /** Batches a building could run in a day: its staff and level, halved without power or water. */
    public static double batches(Building b, TownModel m) {
        if (of(b) == null || !b.working() || b.fire > 0) return 0;
        double n = m.staff(b) * BATCHES_PER_WORKER * LEVEL[Math.max(0, Math.min(LEVEL.length - 1, b.level - 1))];
        if (!m.util.power(b) || !m.util.water(b)) n *= 0.5;
        return n;
    }

    public static int capacity(int storehouses) {
        return BASE_CAPACITY + PER_STOREHOUSE * storehouses;
    }

    /** The stock as counts per material. */
    public static Map<Material, Integer> counts(Map<Identifier, Integer> stock) {
        Map<Material, Integer> out = new EnumMap<>(Material.class);
        for (Material mat : Material.values()) out.put(mat, Stock.count(stock, mat));
        return out;
    }

    /** One building's part of a day: the batches it could run, those it did, and what it was short of. */
    public record Flow(Building building, Trade trade, double batches, double ran, @Nullable Material shortOf) {}

    /** A day (or a share of one) of the town's industry. */
    public record Day(List<Flow> flows, Map<Material, Double> made, Map<Material, Double> used, Map<Material, Double> exported,
                      int capacity, double share) {
        public double made(Material m) { return made.getOrDefault(m, 0.0); }
        public double used(Material m) { return used.getOrDefault(m, 0.0); }
        public double exported(Material m) { return exported.getOrDefault(m, 0.0); }
        /** What the storehouse gains (or loses) of a material. */
        public double net(Material m) { return made(m) - used(m) - exported(m); }

        /** What the surplus sells for, in hundredths of an emerald. */
        public double exportValue() {
            double v = 0;
            for (var e : exported.entrySet()) v += e.getValue() * e.getKey().price;
            return v;
        }

        public @Nullable Flow flow(Building b) {
            for (Flow f : flows) if (f.building().id == b.id) return f;
            return null;
        }

        public boolean any() { return !flows.isEmpty(); }
    }

    /**
     * Runs the town's industry for {@code share} of a day against the stock: raw trades first, then processors in
     * trade order (so the carpenter's planks can feed the cooper the same day), each running only what the
     * storehouse can supply. What would overflow the storehouse is sold.
     */
    public static Day day(TownModel m, Map<Material, Integer> stock, int storehouses, double share) {
        Map<Material, Double> made = new EnumMap<>(Material.class), used = new EnumMap<>(Material.class), exported = new EnumMap<>(Material.class);
        Map<Material, Double> avail = new EnumMap<>(Material.class);
        for (Material mat : Material.values()) avail.put(mat, (double) stock.getOrDefault(mat, 0));
        List<Building> workers = new ArrayList<>();
        for (Building b : m.town) if (of(b) != null && b.working()) workers.add(b);
        workers.sort(Comparator.comparingInt((Building b) -> of(b).ordinal()).thenComparingInt(b -> b.id));
        List<Flow> flows = new ArrayList<>();
        for (Building b : workers) {
            Trade t = of(b);
            Recipe r = recipe(t, b.level, b.style);
            double want = batches(b, m) * share, ran = want;
            Material shortOf = null;
            for (var e : r.in().entrySet()) {
                double can = avail.get(e.getKey()) / e.getValue();
                if (can < ran) { ran = can; shortOf = e.getKey(); }
            }
            ran = Math.max(0, ran);
            for (var e : r.in().entrySet()) {
                avail.merge(e.getKey(), -ran * e.getValue(), Double::sum);
                used.merge(e.getKey(), ran * e.getValue(), Double::sum);
            }
            for (var e : r.out().entrySet()) {
                avail.merge(e.getKey(), ran * e.getValue(), Double::sum);
                made.merge(e.getKey(), ran * e.getValue(), Double::sum);
            }
            flows.add(new Flow(b, t, want / Math.max(share, 1e-9), ran / Math.max(share, 1e-9), want > 0 && ran < want * 0.999 ? shortOf : null));
        }
        int cap = capacity(storehouses);
        for (var e : made.entrySet()) {
            double over = avail.get(e.getKey()) - cap;
            if (over > 0) exported.put(e.getKey(), Math.min(over, e.getValue()));
        }
        return new Day(flows, made, used, exported, cap, share);
    }

    private Industry() {}
}
