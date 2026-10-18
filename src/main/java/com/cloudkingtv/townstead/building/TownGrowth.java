package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.city.Stock;
import com.cloudkingtv.townstead.network.LandSnapshotPayload;
import com.cloudkingtv.townstead.road.Material;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadRaster;
import com.cloudkingtv.townstead.zone.LandData;
import com.cloudkingtv.townstead.zone.ZoneRules;
import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The town grows itself: when a zone has demand and a zoned lot is free, a construction site opens there, and
 * sites put up their building one block at a time from the Town Storehouse. Everything placed is a real block.
 * Sites in unloaded chunks simply wait until someone is near.
 */
@EventBusSubscriber(modid = Townstead.MODID)
public final class TownGrowth {
    /** Ticks between growth checks per town. */
    private static final int GROWTH_INTERVAL = 40;
    /** Ticks between placed blocks on a site (4 = five blocks a second, about 40 s for a cottage). */
    public static final int BUILD_INTERVAL = 4;
    private static final int MAX_SITES = 2, MAX_SLOPE = 4, SITE_CLEAR_HEIGHT = 16;

    private static final Map<Integer, BuildingGenerator.Plan> PLANS = new HashMap<>();
    private static final Map<Integer, Set<BlockPos>> PLANNED = new HashMap<>();
    private static boolean landDirty;

    public static BuildingGenerator.Plan plan(Building b) {
        return PLANS.computeIfAbsent(b.id, id -> BuildingGenerator.plan(b));
    }

    private static Set<BlockPos> planned(Building b) {
        return PLANNED.computeIfAbsent(b.id, id -> plan(b).positions());
    }

    public static void forget(int buildingId) {
        PLANS.remove(buildingId);
        PLANNED.remove(buildingId);
        SITE_ROADS.remove(buildingId);
    }

    public static void markDirty() {
        landDirty = true;
    }

    @SubscribeEvent
    static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long time = level.getGameTime();
        CityManager cities = CityManager.get(level.getServer());
        LandData land = LandData.get(level);
        if (time % BUILD_INTERVAL == 0) {
            for (Building b : List.copyOf(land.buildings())) {
                if (b.state != Building.State.ACTIVE) cities.byId(b.city).ifPresent(city -> build(level, city, land, b));
            }
        }
        if (time % GROWTH_INTERVAL == 0) {
            for (City city : cities.all()) {
                if (city.dimension().equals(level.dimension())) grow(level, city, land);
            }
        }
        if (time % 20 == 0) {
            cities.flush(level.getServer());
            if (landDirty) {
                landDirty = false;
                PacketDistributor.sendToPlayersInDimension(level, LandSnapshotPayload.of(land));
            }
        }
    }

    // ---------------------------------------------------------------- growth

    private static void grow(ServerLevel level, City city, LandData land) {
        List<Building> mine = land.buildingsOf(city.id());
        if (mine.stream().filter(b -> b.state != Building.State.ACTIVE).count() >= MAX_SITES) return;
        Demand demand = Demand.of(mine);
        Map<Long, RoadRaster.Cell> roads = ZoneRules.roadColumns(RoadNetwork.get(level), city.id());
        Set<com.cloudkingtv.townstead.city.TilePos> tiles = city.tiles();
        List<ZoneType> order = new ArrayList<>(List.of(ZoneType.values()));
        order.sort(Comparator.comparingInt((ZoneType z) -> demand.of(z)).reversed());
        for (ZoneType zone : order) {
            if (demand.of(zone) < Demand.GROWTH_THRESHOLD) continue;
            // Free zoned lots, nearest the Town Hall first: towns grow outward from their centre.
            List<long[]> lots = new ArrayList<>();
            for (var e : land.zones().entrySet()) {
                if (!e.getValue().city().equals(city.id()) || e.getValue().type() != zone || land.buildingAt(e.getKey()) != null) continue;
                int cx = RoadRaster.keyX(e.getKey()), cz = RoadRaster.keyZ(e.getKey());
                long dx = cx * 8L + 4 - city.townHall().getX(), dz = cz * 8L + 4 - city.townHall().getZ();
                lots.add(new long[] {e.getKey(), dx * dx + dz * dz});
            }
            lots.sort(Comparator.comparingLong(l -> l[1]));
            for (long[] lot : lots) {
                int cx = RoadRaster.keyX(lot[0]), cz = RoadRaster.keyZ(lot[0]);
                Direction front = ZoneRules.frontage(cx, cz, roads, tiles);
                if (front == null || !level.hasChunk(cx >> 1, cz >> 1)) continue;
                Integer baseY = siteHeight(level, cx, cz, roads);
                if (baseY == null) continue;
                startSite(level, city, land, zone, cx, cz, front, baseY, roads);
                return;
            }
        }
    }

    /** Floor level for a lot, or null if the ground is too steep, too wet, or something's in the way. */
    private static @Nullable Integer siteHeight(ServerLevel level, int cx, int cz, Map<Long, RoadRaster.Cell> roads) {
        int[] ys = new int[64];
        int wet = 0, i = 0;
        for (int x = cx * 8; x < cx * 8 + 8; x++) {
            for (int z = cz * 8; z < cz * 8 + 8; z++) {
                if (roads.containsKey(RoadRaster.key(x, z))) continue;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos p = new BlockPos(x, top, z);
                BlockState s = level.getBlockState(p);
                if (!s.getFluidState().isEmpty()) wet++;
                if (s.is(BlockTags.LOGS)) top = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1; // a trunk isn't ground
                for (int y = top - 1; y <= top + 3; y++) {
                    BlockPos q = new BlockPos(x, y, z);
                    BlockState t = level.getBlockState(q);
                    if (t.hasBlockEntity() && level.getBlockEntity(q) != null) return null;
                    if (t.getBlock().builtInRegistryHolder().key().identifier().getNamespace().equals(Townstead.MODID)) return null;
                }
                ys[i++] = top;
            }
        }
        if (wet > 8 || i < 40) return null;
        ys = Arrays.copyOf(ys, i);
        Arrays.sort(ys);
        if (ys[i - 1 - i / 8] - ys[i / 8] > MAX_SLOPE) return null;
        return ys[i / 2] + 1;
    }

    private static void startSite(ServerLevel level, City city, LandData land, ZoneType zone, int cx, int cz, Direction front, int baseY,
                                  Map<Long, RoadRaster.Cell> roads) {
        Building b = new Building(land.newId(), city.id(), zone, cx, cz, front, baseY, level.getRandom().nextLong(), level.getGameTime(),
                ZoneRules.setback(cx, cz, front, roads));
        BuildingGenerator.Plan plan = plan(b);
        b.households = plan.households();
        b.jobs = plan.jobs();
        // Level the lot: clear above the floor, fill below it, grass on top. Felled logs go to the storehouse.
        Map<net.minecraft.world.item.Item, Integer> felled = new HashMap<>();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        Set<Long> siteRoads = new java.util.HashSet<>();
        for (int x = cx * 8; x < cx * 8 + 8; x++) {
            for (int z = cz * 8; z < cz * 8 + 8; z++) {
                if (roads.containsKey(RoadRaster.key(x, z))) { siteRoads.add(RoadRaster.key(x, z)); continue; } // never dig up a road
                for (int y = baseY; y < baseY + SITE_CLEAR_HEIGHT; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir()) continue;
                    if (s.is(BlockTags.LOGS)) felled.merge(s.getBlock().asItem(), 1, Integer::sum);
                    level.setBlock(p, air, Block.UPDATE_ALL);
                }
                for (int y = baseY - 2; y > baseY - 10; y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState s = level.getBlockState(p);
                    if (!(s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced())) break;
                    level.setBlock(p, dirt, Block.UPDATE_ALL);
                }
                BlockPos top = new BlockPos(x, baseY - 1, z);
                BlockState t = level.getBlockState(top);
                if (t.isAir() || t.canBeReplaced() || t.is(Blocks.DIRT) || !t.getFluidState().isEmpty()) level.setBlock(top, grass, Block.UPDATE_ALL);
            }
        }
        if (!felled.isEmpty()) {
            CityManager.get(level.getServer()).editStockQuiet(city, stock -> felled.forEach((item, n) -> Stock.add(stock, item, n)));
        }
        SITE_ROADS.put(b.id, siteRoads);
        verge(level, plan, front, roads);
        land.putBuilding(b);
        markDirty();
        BlockPos centre = new BlockPos(cx * 8 + 4, baseY, cz * 8 + 4);
        level.playSound(null, centre, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 0.6f, 0.7f);
        tell(level, city, Component.translatable("building.townstead.site_started", Component.translatable("building.townstead.kind." + plan.kind())), true);
    }

    /** A dirt path from the front door across the grass verge to the road. */
    private static void verge(ServerLevel level, BuildingGenerator.Plan plan, Direction front, Map<Long, RoadRaster.Cell> roads) {
        BlockPos door = null;
        for (BuildingGenerator.Step s : plan.steps()) if (s.state().getBlock() instanceof DoorBlock) { door = s.pos(); break; }
        if (door == null) return;
        for (int n = 1; n <= 16; n++) {
            BlockPos p = door.relative(front, n);
            if (roads.containsKey(RoadRaster.key(p.getX(), p.getZ()))) {
                // A lamp post where the path meets the walk would block the way in: move it along the road.
                RoadEditor.clearDecorationFor(level, p.getX(), p.getZ(), front.getClockWise());
                return;
            }
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()) - 1;
            BlockPos ground = new BlockPos(p.getX(), top, p.getZ());
            BlockState g = level.getBlockState(ground);
            if (Math.abs(top - (door.getY() - 1)) > 2) return;
            if (g.is(Blocks.GRASS_BLOCK) || g.is(Blocks.DIRT)) {
                level.setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
                BlockPos above = ground.above();
                if (level.getBlockState(above).canBeReplaced()) level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    /** Road columns inside a lot (its edge row may be road): yard work there is skipped. */
    private static final Map<Integer, Set<Long>> SITE_ROADS = new HashMap<>();

    private static Set<Long> siteRoads(ServerLevel level, Building b) {
        return SITE_ROADS.computeIfAbsent(b.id, id -> {
            Map<Long, RoadRaster.Cell> roads = ZoneRules.roadColumns(RoadNetwork.get(level), b.city);
            Set<Long> out = new java.util.HashSet<>();
            for (int x = b.cellX * 8; x < b.cellX * 8 + 8; x++) for (int z = b.cellZ * 8; z < b.cellZ * 8 + 8; z++) {
                if (roads.containsKey(RoadRaster.key(x, z))) out.add(RoadRaster.key(x, z));
            }
            return out;
        });
    }

    // ---------------------------------------------------------------- construction

    private static void build(ServerLevel level, City city, LandData land, Building b) {
        BuildingGenerator.Plan plan = plan(b);
        BlockPos first = plan.steps().getFirst().pos();
        if (!level.hasChunkAt(first) || !level.hasChunkAt(plan.max())) return;
        if (b.progress >= plan.steps().size()) {
            finish(level, city, b, plan);
            return;
        }
        BuildingGenerator.Step step = plan.steps().get(b.progress);
        if (step.pos().getY() <= b.baseY && siteRoads(level, b).contains(RoadRaster.key(step.pos().getX(), step.pos().getZ()))) {
            b.progress++; // yard work that would land on the road
            return;
        }
        BlockState here = level.getBlockState(step.pos());
        if (here.is(step.state().getBlock())) { // panes, fences and stairs reshape to their neighbours
            b.progress++;
            land.setDirty();
            return;
        }
        Material cost = BuildCost.of(step.state());
        boolean free = isFree(level, city);
        if (cost != null && !free) {
            if (Stock.count(city.stock(), cost) < 1) {
                if (b.state != Building.State.PAUSED || b.needs != cost) {
                    b.state = Building.State.PAUSED;
                    b.needs = cost;
                    land.setDirty();
                    markDirty();
                    tell(level, city, Component.translatable("building.townstead.paused",
                            Component.translatable("building.townstead.kind." + plan.kind()), cost.displayName()), false);
                }
                return;
            }
            CityManager.get(level.getServer()).editStockQuiet(city, stock -> Stock.consume(stock, Map.of(cost, 1)));
        }
        raiseScaffold(level, b, plan, step.pos().getY());
        if (step.state().getBlock() instanceof DoorBlock) {
            level.setBlock(step.pos().above(), step.state().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        level.setBlock(step.pos(), step.state(), Block.UPDATE_ALL);
        SoundType sound = step.state().getSoundType();
        level.playSound(null, step.pos(), sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1f) / 4f, sound.getPitch() * 0.8f);
        if (b.progress % 3 == 0) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, step.state()), step.pos().getX() + 0.5, step.pos().getY() + 0.5,
                    step.pos().getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.05);
        }
        b.progress++;
        if (b.state == Building.State.PAUSED) {
            b.state = Building.State.CONSTRUCTING;
            b.needs = null;
        }
        land.setDirty();
        if (b.progress % 8 == 0) markDirty();
    }

    /** Scaffolding beside the walls tracks the height being built. */
    private static void raiseScaffold(ServerLevel level, Building b, BuildingGenerator.Plan plan, int y) {
        Set<BlockPos> planned = planned(b);
        for (BlockPos base : plan.scaffold()) {
            for (int sy = base.getY(); sy <= Math.min(y + 1, base.getY() + 8); sy++) {
                BlockPos p = new BlockPos(base.getX(), sy, base.getZ());
                if (planned.contains(p)) break;
                BlockState s = level.getBlockState(p);
                if (s.is(Blocks.SCAFFOLDING)) continue;
                if (!s.isAir() && !s.canBeReplaced()) break;
                level.setBlock(p, Blocks.SCAFFOLDING.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }

    static void removeScaffold(ServerLevel level, BuildingGenerator.Plan plan) {
        for (BlockPos base : plan.scaffold()) {
            for (int sy = base.getY() + 10; sy >= base.getY(); sy--) {
                BlockPos p = new BlockPos(base.getX(), sy, base.getZ());
                if (level.getBlockState(p).is(Blocks.SCAFFOLDING)) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
    }

    private static void finish(ServerLevel level, City city, Building b, BuildingGenerator.Plan plan) {
        removeScaffold(level, plan);
        b.state = Building.State.ACTIVE;
        b.needs = null;
        LandData.get(level).setDirty();
        markDirty();
        BlockPos door = plan.steps().getFirst().pos();
        level.playSound(null, door, SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 0.8f, 1.0f);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, (plan.min().getX() + plan.max().getX()) / 2.0 + 0.5, plan.max().getY() + 1,
                (plan.min().getZ() + plan.max().getZ()) / 2.0 + 0.5, 20, 2, 1, 2, 0.02);
        Component kind = Component.translatable("building.townstead.kind." + plan.kind());
        tell(level, city, b.zone == ZoneType.RESIDENTIAL
                ? Component.translatable("building.townstead.moved_in", kind, b.households * Demand.PEOPLE_PER_HOUSEHOLD)
                : Component.translatable("building.townstead.opened", kind, b.jobs), true);
    }

    static boolean isFree(ServerLevel level, City city) {
        if (TownsteadConfig.FREE_BUILD.get()) return true;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(city.owner());
        return owner != null && owner.getAbilities().instabuild;
    }

    private static void tell(ServerLevel level, City city, Component message, boolean ok) {
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(city.owner());
        if (owner != null) RoadEditor.notify(owner, message, ok);
    }

    private TownGrowth() {}
}
