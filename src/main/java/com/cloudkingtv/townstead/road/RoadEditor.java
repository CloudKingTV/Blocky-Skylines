package com.cloudkingtv.townstead.road;

import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.city.Stock;
import com.cloudkingtv.townstead.city.TilePos;
import com.cloudkingtv.townstead.network.PlannerMessagePayload;
import com.cloudkingtv.townstead.network.RoadSnapshotPayload;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/** Server-side road edits: validates a planner request, builds or removes the road, charges or refunds materials. */
public final class RoadEditor {
    /** Snap distance for joining an existing node, in blocks. */
    public static final double NODE_SNAP = 2.0;
    /** Junctions closer than this to another node merge into it. */
    public static final double MIN_PIECE = 4.0;
    /** Ticks after placement during which bulldozing refunds everything (an "oops" window, like an undo). */
    public static final long FULL_REFUND_TICKS = 6000;

    // ---------------------------------------------------------------- build

    public static void build(ServerPlayer player, City city, RoadType type, RoadCurve curve) {
        ServerLevel level = player.level();
        if (!level.dimension().equals(city.dimension())) return;
        RoadNetwork network = RoadNetwork.get(level);
        RoadNetwork planned = network.copy();
        double length = curve.length();
        if (length < RoadPlan.MIN_LENGTH) { refuse(player, RoadPlan.Issue.TOO_SHORT); return; }
        if (length > RoadPlan.MAX_LENGTH) { refuse(player, RoadPlan.Issue.TOO_LONG); return; }

        // 1. Ends: join a node, split a road, or start fresh.
        Integer startNode = attach(planned, curve.ax(), curve.az());
        Integer endNode = attach(planned, curve.bx(), curve.bz());
        if (startNode != null && startNode.equals(endNode)) { refuse(player, RoadPlan.Issue.TOO_SHORT); return; }

        // 2. Crossings become junctions, splitting both roads.
        List<double[]> cuts = new ArrayList<>(); // {t, nodeId}
        cuts.add(new double[] {0, startNode == null ? -1 : startNode});
        double lastT = 0;
        while (true) {
            Crossing c = firstCrossing(planned, curve, lastT, length, startNode, endNode);
            if (c == null) break;
            int node = c.node != null ? c.node : split(planned, c.segment, c.segmentT);
            cuts.add(new double[] {c.t, node});
            lastT = c.t;
        }
        cuts.add(new double[] {1, endNode == null ? -1 : endNode});

        // 3. One graded piece between each pair of cuts.
        List<RoadSegment> pieces = new ArrayList<>();
        long now = level.getGameTime();
        for (int i = 0; i + 1 < cuts.size(); i++) {
            double t0 = cuts.get(i)[0], t1 = cuts.get(i + 1)[0];
            RoadCurve sub = subCurve(curve, t0, t1);
            Integer a = cuts.get(i)[1] < 0 ? null : (int) cuts.get(i)[1];
            Integer b = cuts.get(i + 1)[1] < 0 ? null : (int) cuts.get(i + 1)[1];
            if (a != null) sub = withStart(sub, planned.node(a));
            if (b != null) sub = withEnd(sub, planned.node(b));
            RoadGrading.Result graded = RoadGrading.grade(level, type, sub,
                    a == null ? null : planned.node(a).y(), b == null ? null : planned.node(b).y());
            if (graded.tooSteep()) { refuse(player, RoadPlan.Issue.TOO_STEEP); return; }
            if (graded.unloaded()) { refuse(player, RoadPlan.Issue.NOT_LOADED); return; }
            int[] h = graded.heights();
            int an = a != null ? a : planned.addNode(sub.ax(), h[0], sub.az()).id();
            int bn = b != null ? b : planned.addNode(sub.bx(), h[h.length - 1], sub.bz()).id();
            if (i + 1 < cuts.size() - 1 && cuts.get(i + 1)[1] < 0) cuts.get(i + 1)[1] = bn;
            pieces.add(planned.addSegment(new RoadSegment(0, city.id(), type, an, bn, sub, h, now, List.of())));
        }

        // 4. Plan the blocks and the bill.
        RoadPlan plan = RoadPlan.of(level, planned, pieces, inTown(city));
        if (!plan.ok()) { refuse(player, plan.issues.iterator().next()); return; }
        boolean free = isFree(player);
        if (!free) {
            Map<Material, Integer> missing = Stock.missing(city.stock(), plan.cost);
            if (!missing.isEmpty()) { refuseMissing(player, missing); return; }
        }

        // 5. Commit: lamps and hedges the new road paves over are gone; record the new ones.
        for (RoadSegment s : List.copyOf(planned.segments())) {
            if (pieces.stream().anyMatch(p -> p.id() == s.id())) {
                planned.replaceSegment(s.withDecorations(plan.decorations.getOrDefault(s.id(), List.of())));
            } else if (s.decorations().stream().anyMatch(d -> plan.columns.containsKey(RoadRaster.key(d.pos().getX(), d.pos().getZ())))) {
                planned.replaceSegment(s.withDecorations(s.decorations().stream()
                        .filter(d -> !plan.columns.containsKey(RoadRaster.key(d.pos().getX(), d.pos().getZ()))).toList()));
            }
        }
        CityManager cities = CityManager.get(level.getServer());
        cities.editStock(level.getServer(), city, stock -> {
            if (!free) {
                Stock.consume(stock, plan.cost);
                plan.yield.forEach((item, n) -> Stock.add(stock, item, n));
            }
        });
        network.adopt(planned);
        apply(level, plan);
        sync(level);
        notify(player, Component.translatable("road.townstead.built", type.displayName(), Math.round(length)), true);
    }

    private static @Nullable Integer attach(RoadNetwork planned, double x, double z) {
        var node = planned.nearestNode(x, z, NODE_SNAP);
        if (node.isPresent()) return node.get().id();
        var hit = planned.nearestSegment(x, z, 0.5);
        if (hit.isEmpty()) return null;
        RoadSegment s = hit.get().segment();
        double len = s.curve().length(), t = hit.get().t();
        if (t * len < MIN_PIECE) return s.a();
        if ((1 - t) * len < MIN_PIECE) return s.b();
        return split(planned, s, t);
    }

    /** Splits a built segment at t, returning the new junction node. Decorations go to the half they sit on. */
    static int split(RoadNetwork planned, RoadSegment s, double t) {
        RoadCurve[] halves = s.curve().split(t);
        RoadNode mid = planned.addNode(halves[0].bx(), s.heightAt(t), halves[0].bz());
        int[] h = s.heights();
        int cut = (int) Math.round(t * (h.length - 1));
        int[] h0 = java.util.Arrays.copyOfRange(h, 0, Math.max(2, cut + 1));
        int[] h1 = java.util.Arrays.copyOfRange(h, Math.min(cut, h.length - 2), h.length);
        h0[h0.length - 1] = mid.y();
        h1[0] = mid.y();
        List<RoadSegment.Decoration> d0 = new ArrayList<>(), d1 = new ArrayList<>();
        for (RoadSegment.Decoration d : s.decorations()) {
            (s.curve().closestT(d.pos().getX() + 0.5, d.pos().getZ() + 0.5) < t ? d0 : d1).add(d);
        }
        planned.removeSegment(s.id());
        planned.addSegment(new RoadSegment(0, s.city(), s.type(), s.a(), mid.id(), halves[0], h0, s.placedAt(), d0));
        planned.addSegment(new RoadSegment(0, s.city(), s.type(), mid.id(), s.b(), halves[1], h1, s.placedAt(), d1));
        return mid.id();
    }

    private record Crossing(double t, @Nullable Integer node, RoadSegment segment, double segmentT) {}

    /** The first place after {@code afterT} where the new centreline crosses a built one. */
    private static @Nullable Crossing firstCrossing(RoadNetwork planned, RoadCurve curve, double afterT, double length,
                                                    @Nullable Integer startNode, @Nullable Integer endNode) {
        int n = Math.max(8, (int) Math.ceil(length));
        Crossing best = null;
        for (RoadSegment s : planned.segments()) {
            if (startNode != null && endNode != null && (s.a() == startNode || s.b() == startNode) && (s.a() == endNode || s.b() == endNode)) continue;
            RoadCurve o = s.curve();
            int m = Math.max(8, (int) Math.ceil(o.length()));
            for (int i = 0; i < n; i++) {
                double t0 = (double) i / n, t1 = (double) (i + 1) / n;
                if (t1 * length <= afterT * length + MIN_PIECE || (1 - t0) * length < MIN_PIECE) continue;
                for (int j = 0; j < m; j++) {
                    double u0 = (double) j / m, u1 = (double) (j + 1) / m;
                    double[] hit = intersect(curve.x(t0), curve.z(t0), curve.x(t1), curve.z(t1), o.x(u0), o.z(u0), o.x(u1), o.z(u1));
                    if (hit == null) continue;
                    double t = t0 + (t1 - t0) * hit[0], u = u0 + (u1 - u0) * hit[1];
                    if (t * length < afterT * length + MIN_PIECE || (1 - t) * length < MIN_PIECE) continue;
                    if (best != null && best.t <= t) continue;
                    double olen = o.length();
                    Integer node = u * olen < MIN_PIECE ? Integer.valueOf(s.a()) : (1 - u) * olen < MIN_PIECE ? Integer.valueOf(s.b()) : null;
                    if (node != null && (node.equals(startNode) || node.equals(endNode))) continue;
                    best = new Crossing(t, node, s, u);
                }
            }
        }
        return best;
    }

    /** Segment-segment intersection; returns {s, u} along each or null. */
    private static double @Nullable [] intersect(double ax, double az, double bx, double bz, double cx, double cz, double dx, double dz) {
        double rx = bx - ax, rz = bz - az, sx = dx - cx, sz = dz - cz;
        double den = rx * sz - rz * sx;
        if (Math.abs(den) < 1e-9) return null;
        double s = ((cx - ax) * sz - (cz - az) * sx) / den;
        double u = ((cx - ax) * rz - (cz - az) * rx) / den;
        return s >= 0 && s <= 1 && u >= 0 && u <= 1 ? new double[] {s, u} : null;
    }

    static RoadCurve subCurve(RoadCurve c, double t0, double t1) {
        RoadCurve tail = t1 >= 1 ? c : c.split(t1)[0];
        if (t0 <= 0) return tail;
        return tail.split(t0 / t1)[1];
    }

    private static RoadCurve withStart(RoadCurve c, RoadNode n) {
        return new RoadCurve(n.x(), n.z(), c.cx(), c.cz(), c.bx(), c.bz());
    }

    private static RoadCurve withEnd(RoadCurve c, RoadNode n) {
        return new RoadCurve(c.ax(), c.az(), c.cx(), c.cz(), n.x(), n.z());
    }

    public static LongPredicate inTown(City city) {
        Set<TilePos> tiles = city.tiles();
        return key -> tiles.contains(TilePos.ofBlock(RoadRaster.keyX(key), RoadRaster.keyZ(key)));
    }

    public static boolean isFree(ServerPlayer player) {
        return player.getAbilities().instabuild || TownsteadConfig.FREE_BUILD.get();
    }

    // ---------------------------------------------------------------- world changes

    static void apply(ServerLevel level, RoadPlan plan) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int i = 0;
        for (RoadPlan.Column col : plan.columns.values()) {
            RoadRaster.Cell cell = col.cell();
            int air = 0;
            for (int y = cell.y() + 1; y < cell.y() + 48 && air < 3; y++) {
                pos.set(cell.x(), y, cell.z());
                if (level.getBlockState(pos).isAir()) { air++; continue; }
                air = 0;
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            BlockState surface = RoadType.surface(cell.part());
            pos.set(cell.x(), cell.y(), cell.z());
            if (col.resurface()) level.setBlock(pos, surface, Block.UPDATE_ALL);
            BlockState fill = col.fillStone() ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            for (int k = 1; k <= col.fill(); k++) level.setBlock(pos.set(cell.x(), cell.y() - k, cell.z()), fill, Block.UPDATE_ALL);
            if (i++ % 6 == 0) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, surface), cell.x() + 0.5, cell.y() + 1.1, cell.z() + 0.5,
                        3, 0.3, 0.1, 0.3, 0.05);
            }
        }
        plan.decorations.values().forEach(list -> list.forEach(d -> place(level, d)));
        plan.columns.values().stream().skip(plan.columns.size() / 2).findFirst().ifPresent(c ->
                level.playSound(null, c.cell().x(), c.cell().y() + 1, c.cell().z(), SoundEvents.ROOTED_DIRT_PLACE, SoundSource.BLOCKS, 1.0f, 0.8f));
    }

    static void place(ServerLevel level, RoadSegment.Decoration d) {
        switch (d.kind()) {
            case LAMP -> {
                level.setBlock(d.pos(), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(d.pos().above(), Blocks.OAK_FENCE.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(d.pos().above(2), Blocks.LANTERN.defaultBlockState(), Block.UPDATE_ALL);
            }
            case HEDGE -> level.setBlock(d.pos(), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true), Block.UPDATE_ALL);
        }
    }

    /** Removes lamp/hedge blocks in the three blocks above a road surface. */
    static void clearDecoration(ServerLevel level, int x, int y, int z) {
        for (int k = 1; k <= 3; k++) {
            BlockPos p = new BlockPos(x, y + k, z);
            BlockState s = level.getBlockState(p);
            boolean ours = s.is(BlockTags.WOODEN_FENCES) || s.is(Blocks.LANTERN)
                    || (s.getBlock() instanceof LeavesBlock && s.getValue(LeavesBlock.PERSISTENT));
            if (ours) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    // ---------------------------------------------------------------- bulldoze

    public static void bulldoze(ServerPlayer player, City city, int segmentId) {
        ServerLevel level = player.level();
        RoadNetwork network = RoadNetwork.get(level);
        RoadSegment seg = network.segment(segmentId);
        if (seg == null || !seg.city().equals(city.id())) return;
        RoadNetwork planned = network.copy();
        Set<Long> columns = new HashSet<>(planned.raster(seg).keySet());
        for (int nodeId : new int[] {seg.a(), seg.b()}) {
            if (planned.needsCap(nodeId)) columns.addAll(planned.cap(nodeId).keySet());
        }
        Map<Long, RoadRaster.Cell> before = planned.winners(columns);
        planned.removeSegment(segmentId);
        planned.pruneNodes();
        Map<Long, RoadRaster.Cell> after = planned.winners(columns);

        Map<Material, Integer> refund = new EnumMap<>(Material.class);
        for (long key : columns) {
            RoadRaster.Cell old = before.get(key);
            if (old == null) continue;
            RoadRaster.Cell now = after.get(key);
            int x = RoadRaster.keyX(key), z = RoadRaster.keyZ(key);
            clearDecoration(level, x, old.y(), z);
            BlockPos pos = new BlockPos(x, old.y(), z);
            if (now == null) {
                BlockState here = level.getBlockState(pos);
                if (here.is(Blocks.DIRT_PATH) || here.is(Blocks.STONE_BRICKS)) {
                    level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                    // Paths go back to grass (the soil stays); only bought materials come back.
                    if (RoadType.material(old.part()) != Material.DIRT) refund.merge(RoadType.material(old.part()), 1, Integer::sum);
                }
            } else if (now.part() != old.part()) {
                level.setBlock(new BlockPos(x, now.y(), z), RoadType.surface(now.part()), Block.UPDATE_ALL);
            }
        }
        for (RoadSegment.Decoration d : seg.decorations()) {
            clearDecoration(level, d.pos().getX(), d.pos().getY() - 1, d.pos().getZ());
            if (d.kind() == RoadSegment.DecorationKind.LAMP) { refund.merge(Material.FENCES, 2, Integer::sum); refund.merge(Material.LANTERNS, 1, Integer::sum); }
            else refund.merge(Material.LEAVES, 1, Integer::sum);
        }
        // Lamps of roads that remain, whose columns this road had covered, come back.
        for (RoadSegment s : planned.segments()) {
            for (RoadSegment.Decoration d : s.decorations()) {
                if (columns.contains(RoadRaster.key(d.pos().getX(), d.pos().getZ()))) place(level, d);
            }
        }
        boolean recent = level.getGameTime() - seg.placedAt() < FULL_REFUND_TICKS;
        boolean free = isFree(player);
        CityManager cities = CityManager.get(level.getServer());
        cities.editStock(level.getServer(), city, stock -> {
            if (free) return;
            refund.forEach((m, n) -> Stock.add(stock, m.icon().getItem(), recent ? n : n / 2));
        });
        network.adopt(planned);
        sync(level);
        level.playSound(null, seg.curve().x(0.5), seg.heightAt(0.5) + 1, seg.curve().z(0.5), SoundEvents.GRAVEL_BREAK, SoundSource.BLOCKS, 1.0f, 0.8f);
        notify(player, Component.translatable(recent ? "road.townstead.bulldozed_full" : "road.townstead.bulldozed",
                seg.type().displayName()), true);
    }

    // ---------------------------------------------------------------- messages and sync

    private static void refuse(ServerPlayer player, RoadPlan.Issue issue) {
        notify(player, Component.translatable(issue.key()), false);
    }

    private static void refuseMissing(ServerPlayer player, Map<Material, Integer> missing) {
        MutableComponent list = Component.empty();
        boolean first = true;
        for (var e : missing.entrySet()) {
            if (!first) list.append(", ");
            list.append(Component.literal(e.getValue() + " ")).append(e.getKey().displayName());
            first = false;
        }
        notify(player, Component.translatable("road.townstead.missing", list), false);
    }

    /** Feedback shown in the planner (the vanilla HUD is hidden while planning). */
    public static void notify(ServerPlayer player, Component message, boolean ok) {
        PacketDistributor.sendToPlayer(player, new PlannerMessagePayload(message, ok));
    }

    public static void sync(ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level, RoadSnapshotPayload.of(RoadNetwork.get(level)));
    }

    public static void syncTo(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, RoadSnapshotPayload.of(RoadNetwork.get(player.level())));
    }

    private RoadEditor() {}
}
