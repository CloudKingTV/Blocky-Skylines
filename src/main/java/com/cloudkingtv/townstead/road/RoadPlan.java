package com.cloudkingtv.townstead.road;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;

/**
 * Everything a road edit will do to the world, worked out before touching it: which columns get paved, how much
 * is filled and dug, the lamps and hedges, the bill of materials and what digging yields. The client runs the same
 * plan for the ghost preview and the cost shown at the cursor; the server re-runs it to build.
 */
public final class RoadPlan {
    /** Deepest fill under a road before it would need a bridge (a later phase). */
    public static final int MAX_FILL = 12;
    /** Highest cut (not counting trees) before it would need a tunnel. */
    public static final int MAX_CUT = 16;
    public static final double MIN_LENGTH = 4, MAX_LENGTH = 256, MAX_TURN = 135;

    public enum Issue {
        TOO_SHORT, TOO_LONG, TOO_SHARP, OUTSIDE_TOWN, NOT_LOADED, TOO_STEEP, TOO_DEEP, TOO_HIGH, OBSTRUCTED;

        public String key() { return "road.townstead.issue." + name().toLowerCase(java.util.Locale.ROOT); }
    }

    public record Column(RoadRaster.Cell cell, int fill, boolean fillStone, boolean resurface) {}

    public final Map<Long, Column> columns = new LinkedHashMap<>();
    public final Map<Integer, List<RoadSegment.Decoration>> decorations = new HashMap<>();
    public final EnumMap<Material, Integer> cost = new EnumMap<>(Material.class);
    public final Map<Item, Integer> yield = new HashMap<>();
    public final EnumSet<Issue> issues = EnumSet.noneOf(Issue.class);

    public boolean ok() { return issues.isEmpty(); }

    /**
     * @param planned  network that already contains {@code pieces}
     * @param inTown   whether a column key ({@link RoadRaster#key}) is on the town's land
     */
    public static RoadPlan of(LevelReader level, RoadNetwork planned, Collection<RoadSegment> pieces, LongPredicate inTown) {
        RoadPlan plan = new RoadPlan();
        Set<Integer> owners = new HashSet<>();
        for (RoadSegment s : pieces) {
            owners.add(s.id());
            owners.add(-s.a() - 1);
            owners.add(-s.b() - 1);
            double len = s.curve().length();
            if (len > MAX_LENGTH) plan.issues.add(Issue.TOO_LONG);
            if (s.curve().turnDegrees() > MAX_TURN) plan.issues.add(Issue.TOO_SHARP);
        }
        Map<Long, RoadRaster.Cell> merged = planned.merged(pieces);
        for (var e : merged.entrySet()) {
            RoadRaster.Cell cell = e.getValue();
            if (!inTown.test(e.getKey())) plan.issues.add(Issue.OUTSIDE_TOWN);
            if (!owners.contains(cell.owner())) continue;
            if (!level.hasChunk(cell.x() >> 4, cell.z() >> 4)) { plan.issues.add(Issue.NOT_LOADED); continue; }
            plan.column(level, e.getKey(), cell);
        }
        for (RoadSegment s : pieces) plan.decorate(level, s);
        return plan;
    }

    private void column(LevelReader level, long key, RoadRaster.Cell cell) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(cell.x(), cell.y(), cell.z());
        BlockState target = RoadType.surface(cell.part());
        BlockState here = level.getBlockState(pos);
        if (protectedBlock(level, pos, here)) issues.add(Issue.OBSTRUCTED);
        boolean resurface = !here.equals(target);
        // A path or grass laid on soil is the same soil reshaped: no cost, nothing dug up.
        boolean reshaped = RoadType.material(cell.part()) == Material.DIRT && yieldOf(here) == Items.DIRT;
        if (resurface && !reshaped) {
            add(RoadType.material(cell.part()), 1);
            // Soil under a road stays as its bed; only what's cut away above the road is collected.
            if (yieldOf(here) != Items.DIRT) harvest(here);
        }
        int fill = 0;
        boolean wet = false;
        for (int y = cell.y() - 1; y > level.getMinY() && needsFill(level.getBlockState(pos.setY(y))); y--) {
            wet |= !level.getBlockState(pos).getFluidState().isEmpty();
            if (++fill > MAX_FILL) { issues.add(Issue.TOO_DEEP); break; }
        }
        if (fill > 0) add(wet ? Material.COBBLESTONE : Material.DIRT, fill);
        int air = 0, cut = 0;
        for (int y = cell.y() + 1; y < cell.y() + 48 && air < 3; y++) {
            BlockState above = level.getBlockState(pos.setY(y));
            if (above.isAir()) { air++; continue; }
            air = 0;
            if (protectedBlock(level, pos, above)) { issues.add(Issue.OBSTRUCTED); break; }
            if (!above.is(BlockTags.LOGS) && !above.is(BlockTags.LEAVES) && ++cut > MAX_CUT) { issues.add(Issue.TOO_HIGH); break; }
            harvest(above);
        }
        columns.put(key, new Column(cell, Math.min(fill, MAX_FILL), wet, resurface));
    }

    private void decorate(LevelReader level, RoadSegment s) {
        List<RoadSegment.Decoration> out = new ArrayList<>();
        double len = s.curve().length();
        RoadType type = s.type();
        int spacing = type.hedgeSpacing > 0 ? type.hedgeSpacing : type.lampSpacing;
        if (spacing <= 0) return;
        int lampEvery = type.hedgeSpacing > 0 ? Math.max(1, type.lampSpacing / type.hedgeSpacing) : 1;
        int index = 0;
        for (double d = spacing / 2.0; d < len - 4; d += spacing, index++) {
            if (d < 5) continue;
            double t = d / len;
            boolean lamp = type.hedgeSpacing == 0 || index % lampEvery == lampEvery / 2;
            // Streets alternate lamps between the two walks; avenues put lamps and hedges in the median.
            RoadType.Part want = type == RoadType.STREET ? RoadType.Part.SIDEWALK : RoadType.Part.MEDIAN;
            double side = index % 2 == 0 ? 1 : -1;
            double u0 = want == RoadType.Part.SIDEWALK ? side * (type.halfWidth() - 0.5) : 0;
            Vec2 dir = s.curve().direction(t);
            Column column = null;
            int x = 0, z = 0;
            // On a diagonal the exact point can land in the next column over: nudge along the road and outward.
            search:
            for (double along : new double[] {0, 0.5, -0.5, 1, -1}) {
                for (double nudge : new double[] {0, 0.4, -0.4}) {
                    double u = u0 + (want == RoadType.Part.SIDEWALK ? side * nudge : nudge);
                    double px = s.curve().x(t) + dir.x * along, pz = s.curve().z(t) + dir.y * along;
                    x = Mth.floor(px - dir.y * u);
                    z = Mth.floor(pz + dir.x * u);
                    Column c = columns.get(RoadRaster.key(x, z));
                    if (c != null && c.cell().owner() == s.id() && c.cell().part() == want) { column = c; break search; }
                }
            }
            if (column == null) continue;
            out.add(new RoadSegment.Decoration(new BlockPos(x, column.cell().y() + 1, z),
                    lamp ? RoadSegment.DecorationKind.LAMP : RoadSegment.DecorationKind.HEDGE));
            if (lamp) { add(Material.FENCES, 2); add(Material.LANTERNS, 1); }
            else add(Material.LEAVES, 1);
        }
        decorations.put(s.id(), out);
    }

    private void add(Material m, int n) {
        cost.merge(m, n, Integer::sum);
    }

    private void harvest(BlockState state) {
        Item item = yieldOf(state);
        if (item != Items.AIR) yield.merge(item, 1, Integer::sum);
    }

    /** What digging a block puts in the storehouse (only building materials are kept). */
    public static Item yieldOf(BlockState state) {
        if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.ROOTED_DIRT)) return Items.DIRT;
        if (state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE)) return Items.COBBLESTONE;
        if (state.is(Blocks.DEEPSLATE) || state.is(Blocks.COBBLED_DEEPSLATE)) return Items.COBBLED_DEEPSLATE;
        if (state.getBlock() instanceof LeavesBlock) return state.getValue(LeavesBlock.PERSISTENT) ? state.getBlock().asItem() : Items.AIR;
        Item item = state.getBlock().asItem();
        return item.builtInRegistryHolder().is(Material.BUILDING_MATERIALS) ? item : Items.AIR;
    }

    /** Things a road never bulldozes on its own: town blocks, and anything with contents (chests, furnaces, beds...). */
    static boolean protectedBlock(LevelReader level, BlockPos pos, BlockState state) {
        if (state.getBlock().builtInRegistryHolder().key().identifier().getNamespace().equals(com.cloudkingtv.townstead.Townstead.MODID)) return true;
        return state.hasBlockEntity() && level.getBlockEntity(pos) != null;
    }

    static boolean needsFill(BlockState state) {
        return state.isAir() || !state.getFluidState().isEmpty() || state.canBeReplaced();
    }
}
