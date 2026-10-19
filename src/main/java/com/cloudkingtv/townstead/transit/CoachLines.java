package com.cloudkingtv.townstead.transit;

import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.network.TransitPayloads;
import com.cloudkingtv.townstead.progress.Milestone;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadRouter;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.zone.LandData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;

/**
 * Drawing and removing coach lines. Every stop is a real stop by the road: a spruce post flying a banner in the
 * line's colour, with a bench beside it where there's room. Removing a line takes its stops away again (only the
 * blocks it placed).
 */
public final class CoachLines {
    public static final int MAX_LINES = 8, MAX_STOPS = 24;
    /** Stops closer than this along the road would be pointless. */
    public static final double MIN_SPACING = 12;
    /** What a stop costs to put up, in hundredths of an emerald. */
    public static final long STOP_COST = 50;

    public static void create(ServerPlayer player, City city, List<TransitPayloads.StopPick> picks) {
        ServerLevel level = player.level();
        if (!Milestone.unlocked(city.milestone(), ServiceType.COACH_HOUSE)) {
            RoadEditor.notify(player, Component.translatable("milestone.townstead.locked", Component.translatable("transit.townstead.lines"),
                    Milestone.unlocking(ServiceType.COACH_HOUSE).displayName()), false);
            return;
        }
        Component problem = problem(city.id(), city.lines().size(), RoadNetwork.get(level), picks);
        if (problem != null) { RoadEditor.notify(player, problem, false); return; }
        long cost = STOP_COST * picks.size();
        boolean free = RoadEditor.isFree(player);
        if (!free && city.treasury() < cost) {
            RoadEditor.notify(player, Component.translatable("transit.townstead.too_poor", com.cloudkingtv.townstead.economy.Budget.format(cost)), false);
            return;
        }
        CityManager cities = CityManager.get(level.getServer());
        java.util.Set<Integer> used = new java.util.HashSet<>();
        for (CoachLine l : city.lines()) used.add(l.color().ordinal());
        net.minecraft.world.item.DyeColor color = CoachLine.PALETTE[0];
        for (var c : CoachLine.PALETTE) if (!used.contains(c.ordinal())) { color = c; break; }
        RoadNetwork net = RoadNetwork.get(level);
        List<CoachLine.Stop> stops = new ArrayList<>();
        for (TransitPayloads.StopPick pick : picks) stops.add(putStop(level, net.segment(pick.segment()), Mth.clamp(pick.t(), 0, 1), color));
        cities.addLine(level.getServer(), city, new CoachLine(0, "", color, stops));
        if (!free) cities.addTreasury(level.getServer(), city, -cost);
        level.playSound(null, stops.getFirst().marker(), SoundEvents.HORSE_SADDLE.value(), SoundSource.BLOCKS, 0.8f, 1.0f);
        boolean depot = LandData.get(level).buildingsOf(city.id()).stream()
                .anyMatch(b -> ServiceType.COACH_HOUSE.key().equals(b.service));
        RoadEditor.notify(player, Component.translatable(depot ? "transit.townstead.created" : "transit.townstead.created_no_depot",
                lineName(color), stops.size()), true);
        com.cloudkingtv.townstead.progress.Chirps.post(level, city, "line", stops.getFirst().marker(), false, lineName(color), city.name());
    }

    public static void remove(ServerPlayer player, City city, int lineId) {
        ServerLevel level = player.level();
        CoachLine line = CityManager.get(level.getServer()).removeLine(level.getServer(), city, lineId);
        if (line == null) return;
        for (CoachLine.Stop s : line.stops()) clearStop(level, s);
        RoadEditor.notify(player, Component.translatable("transit.townstead.removed", lineName(line.color())), true);
    }

    /** "Red line", "Blue line"... */
    public static Component lineName(net.minecraft.world.item.DyeColor color) {
        return Component.translatable("transit.townstead.line_name", Component.translatable("color.minecraft." + color.getName()));
    }

    /** Why these stops can't make a line, or null if they can. Mirrors what the client checks while drawing. */
    public static @org.jspecify.annotations.Nullable Component problem(java.util.UUID cityId, int lineCount, RoadNetwork net,
                                                                      List<TransitPayloads.StopPick> picks) {
        if (lineCount >= MAX_LINES) return Component.translatable("transit.townstead.too_many", MAX_LINES);
        if (picks.size() < 2) return Component.translatable("transit.townstead.too_few");
        if (picks.size() > MAX_STOPS) return Component.translatable("transit.townstead.too_long", MAX_STOPS);
        RoadRouter router = new RoadRouter(net, cityId);
        List<RoadRouter.Access> stops = new ArrayList<>();
        for (TransitPayloads.StopPick p : picks) {
            RoadSegment s = net.segment(p.segment());
            if (s == null || !s.city().equals(cityId)) return Component.translatable("transit.townstead.not_road");
            stops.add(new RoadRouter.Access(p.segment(), Mth.clamp(p.t(), 0, 1)));
        }
        for (int i = 0; i < stops.size(); i++) {
            var route = router.route(stops.get(i), stops.get((i + 1) % stops.size()), router::freeTime);
            if (route == null) return Component.translatable("transit.townstead.unreachable");
            if (router.length(route) < MIN_SPACING) return Component.translatable("transit.townstead.too_close");
        }
        return null;
    }

    /** Puts up a stop beside the road at (segment, t): a post with the line's banner, and a bench if there's room. */
    static CoachLine.Stop putStop(ServerLevel level, RoadSegment s, double t, net.minecraft.world.item.DyeColor color) {
        double x = s.curve().x(t), z = s.curve().z(t);
        Vec2 d = s.curve().direction(t).normalized();
        double nx = -d.y, nz = d.x; // right of the segment's direction
        Direction out = Direction.getApproximateNearest(nx, 0, nz);
        for (double off = s.type().halfWidth() + 0.5; off <= s.type().halfWidth() + 2.5; off += 1) {
            int px = Mth.floor(x + nx * off), pz = Mth.floor(z + nz * off);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, px, pz);
            BlockPos post = new BlockPos(px, y, pz);
            if (!free(level, post, 3) || !level.getBlockState(post.below()).isFaceSturdy(level, post.below(), Direction.UP)) continue;
            if (Math.abs(y - (s.heightAt(t) + 1)) > 2) continue; // not on a bank far above or below the road
            set(level, post, Blocks.SPRUCE_FENCE.defaultBlockState());
            set(level, post.above(), Blocks.SPRUCE_FENCE.defaultBlockState());
            float yaw = (float) Math.toDegrees(Math.atan2(-nx, nz));
            set(level, post.above(2), Blocks.BANNER.pick(color).defaultBlockState()
                    .setValue(BannerBlock.ROTATION, RotationSegment.convertToSegment(yaw + 180)));
            // A bench beside the post, its back to the fields, facing the road.
            Direction along = Direction.getApproximateNearest(d.x, 0, d.y);
            BlockPos bench = post;
            for (BlockPos b : new BlockPos[] {post.relative(along), post.relative(along.getOpposite())}) {
                if (free(level, b, 2) && level.getBlockState(b.below()).isFaceSturdy(level, b.below(), Direction.UP)) {
                    set(level, b, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, out));
                    bench = b;
                    break;
                }
            }
            return new CoachLine.Stop(s.id(), t, post, bench);
        }
        // No room for a post: the stop is just a place on the road.
        BlockPos road = BlockPos.containing(x, s.heightAt(t) + 1, z);
        return new CoachLine.Stop(s.id(), t, road, road);
    }

    private static boolean free(ServerLevel level, BlockPos pos, int height) {
        for (int i = 0; i < height; i++) {
            BlockState st = level.getBlockState(pos.above(i));
            if (!st.canBeReplaced() || !st.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, Block.updateFromNeighbourShapes(state, level, pos), Block.UPDATE_ALL);
    }

    /** Takes a stop's post, banner and bench away, if they're still there. */
    static void clearStop(ServerLevel level, CoachLine.Stop s) {
        BlockPos p = s.marker();
        if (level.getBlockState(p.above(2)).getBlock() instanceof BannerBlock) level.setBlock(p.above(2), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (int i = 1; i >= 0; i--) {
            if (level.getBlockState(p.above(i)).is(Blocks.SPRUCE_FENCE)) level.setBlock(p.above(i), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        if (!s.bench().equals(p) && level.getBlockState(s.bench()).is(Blocks.SPRUCE_STAIRS)) level.setBlock(s.bench(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
    }

    private CoachLines() {}
}
