package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.city.Stock;
import com.cloudkingtv.townstead.city.TilePos;
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
import java.util.HashSet;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The town lives on its own. When a zone has demand and a zoned lot is free, a construction site opens; sites put
 * their building up one block at a time from the Town Storehouse. Buildings that meet their next level's needs are
 * rebuilt bigger in place; buildings in lasting trouble are abandoned (boarded up, cobwebs) and repaired when things
 * improve. Everything placed is a real block. Sites in unloaded chunks keep a tally of steps owed and catch up in
 * one go every so often by loading their chunks briefly, so towns grow while you're away.
 */
@EventBusSubscriber(modid = Townstead.MODID)
public final class TownGrowth {
    /** Ticks between growth checks per town. */
    private static final int GROWTH_INTERVAL = 40;
    /** Ticks between placed blocks on a site (4 = five blocks a second at speed 1). */
    public static final int BUILD_INTERVAL = 4;
    /** Ticks between level-up / trouble checks. */
    private static final int REVIEW_INTERVAL = 100;
    /** Ticks between catch-ups of sites in unloaded chunks. */
    private static final int CATCH_UP_INTERVAL = 600;
    private static final int MAX_SITES = 2, MAX_UPGRADES = 1, MAX_SLOPE = 4, SITE_CLEAR_HEIGHT = 32;
    /** How far a lot may sit above or below its road: the front path climbs it in steps. */
    private static final int PATH_RISE = 2;

    private static final Map<Long, BuildingGenerator.Plan> PLANS = new HashMap<>();
    private static final Map<Long, Set<BlockPos>> PLANNED = new HashMap<>();
    private static final Map<Integer, Set<Long>> SITE_ROADS = new HashMap<>();
    private static boolean landDirty;

    private static long key(Building b) {
        return ((long) b.id << 8) | ((long) b.level << 4) | (b.style.hashCode() & 0xF);
    }

    public static BuildingGenerator.Plan plan(Building b) {
        return PLANS.computeIfAbsent(key(b), k -> BuildingGenerator.plan(b));
    }

    private static Set<BlockPos> planned(Building b) {
        return PLANNED.computeIfAbsent(key(b), k -> plan(b).positions());
    }

    public static void forget(int buildingId) {
        PLANS.keySet().removeIf(k -> (k >> 8) == buildingId);
        PLANNED.keySet().removeIf(k -> (k >> 8) == buildingId);
        SITE_ROADS.remove(buildingId);
    }

    public static void markDirty() {
        landDirty = true;
    }

    public static double speed() {
        return TownsteadConfig.GROWTH_SPEED.get();
    }

    @SubscribeEvent
    static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long time = level.getGameTime();
        CityManager cities = CityManager.get(level.getServer());
        LandData land = LandData.get(level);
        double speed = speed();
        if (time % BUILD_INTERVAL == 0) {
            // Below speed 1, skip build ticks; above it, place several blocks per tick.
            int every = speed < 1 ? (int) Math.round(1 / speed) : 1;
            int perTick = speed < 1 ? 1 : (int) Math.round(speed);
            if ((time / BUILD_INTERVAL) % every == 0) {
                for (Building b : List.copyOf(land.buildings())) {
                    if (b.state == Building.State.ACTIVE || b.state == Building.State.ABANDONED) continue;
                    cities.byId(b.city).ifPresent(city -> {
                        for (int i = 0; i < perTick; i++) if (!build(level, city, land, b, false)) break;
                    });
                }
            }
        }
        if (time % GROWTH_INTERVAL == 0) {
            for (City city : cities.all()) if (city.dimension().equals(level.dimension())) grow(level, city, land);
        }
        if (time % REVIEW_INTERVAL == 0) {
            for (City city : cities.all()) if (city.dimension().equals(level.dimension())) review(level, city, land);
        }
        if (time % CATCH_UP_INTERVAL == 0) catchUp(level, cities, land);
        if (time % 20 == 0) {
            cities.flush(level.getServer());
            if (landDirty) {
                landDirty = false;
                PacketDistributor.sendToPlayersInDimension(level, LandSnapshotPayload.of(land));
            }
        }
    }

    // ---------------------------------------------------------------- new sites

    private static void grow(ServerLevel level, City city, LandData land) {
        List<Building> mine = land.buildingsOf(city.id());
        if (mine.stream().filter(b -> b.state != Building.State.ACTIVE && b.state != Building.State.ABANDONED && !b.upgrading).count() >= MAX_SITES) return;
        Demand demand = com.cloudkingtv.townstead.economy.TownEconomy.model(level, city).demand;
        Map<Long, RoadRaster.Cell> roads = ZoneRules.roadColumns(RoadNetwork.get(level), city.id());
        Set<TilePos> tiles = city.tiles();
        List<ZoneType> order = new ArrayList<>(ZoneType.PAINTABLE);
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
            java.util.function.LongPredicate free = k -> {
                LandData.Zoned z = land.zone(k);
                return z != null && z.city().equals(city.id()) && z.type() == zone && land.buildingAt(k) == null;
            };
            for (long[] lot : lots) {
                int cx = RoadRaster.keyX(lot[0]), cz = RoadRaster.keyZ(lot[0]);
                Direction front = ZoneRules.frontage(cx, cz, roads, tiles);
                if (front == null) continue;
                // A lot shape for this zone (a cottage plot, a wide farmhouse plot, a deep yard...), then smaller ones.
                var spec = city.civics().spec(lot[0]);
                for (int[] shape : shapes(zone, spec.need == zone.need ? spec : com.cloudkingtv.townstead.district.Specialization.NONE,
                        lot[0] * 31 + land.buildings().size())) {
                    if (!ZoneRules.lotFits(cx, cz, front, shape[0], shape[1], roads, tiles, free)) continue;
                    List<Long> cells = Building.lotCells(cx, cz, front, shape[0], shape[1]);
                    for (long k : cells) level.getChunk(RoadRaster.keyX(k) >> 1, RoadRaster.keyZ(k) >> 1); // towns grow while you're away
                    Integer baseY = siteHeight(level, cells, roads);
                    if (baseY == null) continue;
                    startSite(level, city, land, zone, "", cx, cz, front, shape[0], shape[1], baseY, roads);
                    return;
                }
            }
        }
    }

    /** Lot shapes ({wide, deep}) to try for a zone, the weighted pick first, then smaller ones down to a single cell. */
    static List<int[]> shapes(ZoneType zone, long seed) {
        return shapes(zone, com.cloudkingtv.townstead.district.Specialization.NONE, seed);
    }

    /** As {@link #shapes(ZoneType, long)}; farms, lumber yards and quarries want room for their fields, stacks and pits. */
    static List<int[]> shapes(ZoneType zone, com.cloudkingtv.townstead.district.Specialization spec, long seed) {
        int[][] all = {{2, 2}, {2, 1}, {1, 2}, {1, 1}};
        int[] weights = spec == com.cloudkingtv.townstead.district.Specialization.FARMING ? new int[] {60, 30, 10, 1}
                : spec == com.cloudkingtv.townstead.district.Specialization.FORESTRY ? new int[] {40, 40, 20, 1}
                : spec == com.cloudkingtv.townstead.district.Specialization.QUARRY ? new int[] {55, 35, 10, 1} : switch (zone) {
            case RESIDENTIAL -> new int[] {10, 30, 20, 40};
            case RESIDENTIAL_HIGH -> new int[] {35, 45, 10, 10};
            case COMMERCIAL -> new int[] {0, 35, 15, 50};
            case COMMERCIAL_HIGH -> new int[] {40, 45, 0, 15};
            case INDUSTRIAL -> new int[] {35, 40, 15, 10};
            case OFFICE -> new int[] {40, 45, 0, 15};
            case CIVIC -> new int[] {0, 0, 0, 1};
        };
        int total = 0;
        for (int w : weights) total += w;
        int r = (int) Math.floorMod(seed * 0x9E3779B97F4A7C15L >>> 17, (long) total), pick = 0;
        for (int i = 0; i < weights.length; i++) { if (r < weights[i]) { pick = i; break; } r -= weights[i]; }
        List<int[]> out = new ArrayList<>();
        out.add(all[pick]);
        for (int i = pick + 1; i < all.length; i++) if (weights[i] > 0 || i == all.length - 1) out.add(all[i]);
        return out;
    }

    /** Floor level for a lot, or null if the ground is too steep, too wet, or something's in the way. */
    private static @Nullable Integer siteHeight(ServerLevel level, List<Long> cells, Map<Long, RoadRaster.Cell> roads) {
        int[] ys = new int[64 * cells.size()];
        int wet = 0, i = 0;
        for (long cell : cells) for (int x = RoadRaster.keyX(cell) * 8; x < RoadRaster.keyX(cell) * 8 + 8; x++) {
            for (int z = RoadRaster.keyZ(cell) * 8; z < RoadRaster.keyZ(cell) * 8 + 8; z++) {
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
        if (wet > 8 * cells.size() || i < 40 * cells.size()) return null;
        ys = Arrays.copyOf(ys, i);
        Arrays.sort(ys);
        if (ys[i - 1 - i / 8] - ys[i / 8] > MAX_SLOPE) return null;
        int ground = ys[i / 2] + 1;
        // Level with the road it faces where the ground allows, so the front path meets the road flush; a little
        // above or below it on a hillside (the path steps the difference); never so far off it the door can't be reached.
        Integer road = roadLevel(cells, roads);
        if (road == null) return ground;
        int off = ground - (road + 1);
        if (Math.abs(off) <= MAX_SLOPE) return road + 1;
        if (Math.abs(off) > MAX_SLOPE + PATH_RISE) return null;
        return road + 1 + Integer.signum(off) * (Math.abs(off) - MAX_SLOPE);
    }

    /** The surface height of the road beside a lot (median of its columns within a few blocks), or null if none. */
    private static @Nullable Integer roadLevel(List<Long> cells, Map<Long, RoadRaster.Cell> roads) {
        List<Integer> ys = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (long cell : cells) {
            int cx = RoadRaster.keyX(cell) * 8, cz = RoadRaster.keyZ(cell) * 8;
            for (int x = cx - 3; x < cx + 11; x++) for (int z = cz - 3; z < cz + 11; z++) {
                long k = RoadRaster.key(x, z);
                RoadRaster.Cell c = roads.get(k);
                if (c != null && seen.add(k)) ys.add(c.y());
            }
        }
        if (ys.isEmpty()) return null;
        ys.sort(null);
        return ys.get(ys.size() / 2);
    }

    /**
     * Eases the ground just outside a levelled lot back to the natural terrain over a few blocks, so a lot cut into a
     * slope or raised above it has a bank instead of a cliff. Only natural ground is touched: never roads, other
     * lots, trees or anything built.
     */
    private static void blendEdges(ServerLevel level, Building b, LandData land, Map<Long, RoadRaster.Cell> roads) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (long k : b.cellKeys()) {
            x0 = Math.min(x0, RoadRaster.keyX(k) * 8); z0 = Math.min(z0, RoadRaster.keyZ(k) * 8);
            x1 = Math.max(x1, RoadRaster.keyX(k) * 8 + 7); z1 = Math.max(z1, RoadRaster.keyZ(k) * 8 + 7);
        }
        final int reach = 3, lot = b.baseY - 1;
        for (int x = x0 - reach; x <= x1 + reach; x++) for (int z = z0 - reach; z <= z1 + reach; z++) {
            int d = Math.max(Math.max(x0 - x, x - x1), Math.max(z0 - z, z - z1));
            if (d <= 0 || roads.containsKey(RoadRaster.key(x, z))) continue;
            Building other = land.buildingAt(ZoneRules.cellKey(Math.floorDiv(x, 8), Math.floorDiv(z, 8)));
            if (other != null && other != b) continue;
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            BlockPos tp = new BlockPos(x, top, z);
            if (!natural(level.getBlockState(tp)) || Math.abs(top - lot) > 6) continue;
            int target = (int) Math.round(lot + (top - lot) * (d / (double) (reach + 1)));
            if (target == top) continue;
            if (top > target) {
                boolean allNatural = true;
                for (int y = target + 1; y <= top; y++) if (!natural(level.getBlockState(new BlockPos(x, y, z)))) allNatural = false;
                if (!allNatural) continue;
                for (int y = top; y > target; y--) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            } else {
                for (int y = top; y < target; y++) level.setBlock(new BlockPos(x, y, z), Blocks.DIRT.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            level.setBlock(new BlockPos(x, target, z), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            BlockPos above = new BlockPos(x, target + 1, z);
            if (level.getBlockState(above).canBeReplaced() && !level.getBlockState(above).isAir()) level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Ground the world made: earth, stone, sand, snow, boulders and the plants on them. */
    private static boolean natural(BlockState s) {
        return s.is(BlockTags.SUBSTRATE_OVERWORLD) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.SAND)
                || s.is(Blocks.GRAVEL) || s.is(Blocks.MOSSY_COBBLESTONE) || s.is(Blocks.SNOW) || s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.CLAY)
                || s.is(BlockTags.SMALL_FLOWERS) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS) || s.is(Blocks.FERN);
    }

    /**
     * Places a service or utility the mayor chose: same site preparation and construction as growables.
     * Returns the new building, or null with a reason key when the lot can't take it.
     */
    public static Object placeCivic(ServerLevel level, City city, com.cloudkingtv.townstead.service.ServiceType service, int cx, int cz) {
        LandData land = LandData.get(level);
        Map<Long, RoadRaster.Cell> roads = ZoneRules.roadColumns(RoadNetwork.get(level), city.id());
        if (land.buildingAt(ZoneRules.cellKey(cx, cz)) != null) return "service.townstead.lot_taken";
        if (service.landmark() && land.buildingsOf(city.id()).stream().anyMatch(o -> service.key().equals(o.service))) return "service.townstead.only_one";
        Direction front = ZoneRules.frontage(cx, cz, roads, city.tiles());
        if (front == null) return "zone.townstead.no_frontage";
        // The clicked cell anywhere along the front row of the service's lot.
        Direction right = front.getCounterClockWise();
        int ax = cx, az = cz;
        boolean fits = false;
        for (int shift = 0; shift < service.wide && !fits; shift++) {
            ax = cx - right.getStepX() * shift;
            az = cz - right.getStepZ() * shift;
            fits = ZoneRules.lotFits(ax, az, front, service.wide, service.deep, roads, city.tiles(), k -> land.buildingAt(k) == null);
        }
        if (!fits) return "service.townstead.no_room";
        List<Long> cells = Building.lotCells(ax, az, front, service.wide, service.deep);
        for (long k : cells) level.getChunk(RoadRaster.keyX(k) >> 1, RoadRaster.keyZ(k) >> 1);
        if (service == com.cloudkingtv.townstead.service.ServiceType.WATERMILL
                && cells.stream().noneMatch(k -> waterNear(level, RoadRaster.keyX(k), RoadRaster.keyZ(k)))) return "service.townstead.needs_water";
        Integer baseY = siteHeight(level, cells, roads);
        if (baseY == null) return "service.townstead.bad_ground";
        for (long k : cells) land.setZone(k, null); // a service replaces any zoning on its lot
        return startSite(level, city, land, ZoneType.CIVIC, service.key(), ax, az, front, service.wide, service.deep, baseY, roads);
    }

    /** Running water (a river or lake) within a few blocks of the lot, for a watermill. */
    public static boolean waterNear(net.minecraft.world.level.LevelReader level, int cx, int cz) {
        for (int x = cx * 8 - 6; x < cx * 8 + 14; x++) {
            for (int z = cz * 8 - 6; z < cz * 8 + 14; z++) {
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                if (level.getFluidState(new BlockPos(x, top, z)).is(net.minecraft.tags.FluidTags.WATER)) return true;
            }
        }
        return false;
    }

    private static Building startSite(ServerLevel level, City city, LandData land, ZoneType zone, String service, int cx, int cz, Direction front,
                                      int wide, int deep, int baseY, Map<Long, RoadRaster.Cell> roads) {
        int setback = 0;
        for (long k : Building.lotCells(cx, cz, front, wide, 1)) setback = Math.max(setback, ZoneRules.setback(RoadRaster.keyX(k), RoadRaster.keyZ(k), front, roads));
        Building b = new Building(land.newId(), city.id(), zone, cx, cz, front, baseY, level.getRandom().nextLong(), level.getGameTime(), setback);
        b.service = service;
        b.wide = wide;
        b.deep = deep;
        b.style = Palette.styleAt(level, BlockPos.containing(b.centerX(), baseY, b.centerZ()));
        // A district's specialisation shapes the workshops (or shops) started in it.
        var spec = city.civics().spec(ZoneRules.cellKey(cx, cz));
        if (spec.need != null && spec.need == zone.need) b.spec = spec.key();
        BuildingGenerator.Plan plan = plan(b);
        b.households = plan.households();
        b.jobs = plan.jobs();
        // Level the lot: clear above the floor, fill below it, grass on top. Felled logs go to the storehouse.
        Map<Item, Integer> felled = new HashMap<>();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        Set<Long> siteRoads = new HashSet<>();
        for (long cell : b.cellKeys()) for (int x = RoadRaster.keyX(cell) * 8; x < RoadRaster.keyX(cell) * 8 + 8; x++) {
            for (int z = RoadRaster.keyZ(cell) * 8; z < RoadRaster.keyZ(cell) * 8 + 8; z++) {
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
        blendEdges(level, b, land, roads);
        frontPath(level, b, plan, roads);
        land.putBuilding(b);
        markDirty();
        BlockPos centre = BlockPos.containing(b.centerX(), baseY, b.centerZ());
        level.playSound(null, centre, SoundEvents.WOODEN_DOOR_OPEN, SoundSource.BLOCKS, 0.6f, 0.7f);
        tell(level, city, Component.translatable("building.townstead.site_started", kindName(plan)), true);
        return b;
    }

    /** The bill of materials for a whole plan (for previews). */
    public static Map<Material, Integer> bill(BuildingGenerator.Plan plan) {
        Map<Material, Integer> out = new java.util.EnumMap<>(Material.class);
        for (BuildingGenerator.Step s : plan.steps()) {
            Material m = BuildCost.of(s.state());
            if (m != null) out.merge(m, 1, Integer::sum);
        }
        return out;
    }

    public static Component kindName(BuildingGenerator.Plan plan) {
        return Component.translatable("building.townstead.kind." + plan.kind());
    }

    /**
     * A walkable path from the front door straight out to the road: level with the floor as long as it can be, then
     * stepping (with stairs) to meet the road, cut into a bank or banked up over a dip as needed. Laid when the site
     * starts and again when the building is finished, in case anything settled on it meanwhile.
     */
    private static void frontPath(ServerLevel level, Building b, BuildingGenerator.Plan plan, Map<Long, RoadRaster.Cell> roads) {
        BlockPos door = null;
        for (BuildingGenerator.Step s : plan.steps()) {
            if (s.state().getBlock() instanceof DoorBlock && s.pos().getY() == b.baseY) { door = s.pos(); break; }
        }
        if (door == null) return;
        Direction front = b.front;
        int reach = 0;
        RoadRaster.Cell road = null;
        for (int n = 1; n <= 16 && road == null; n++) {
            BlockPos p = door.relative(front, n);
            road = roads.get(RoadRaster.key(p.getX(), p.getZ()));
            reach = n;
        }
        if (road == null || reach < 2) return;
        // A lamp post where the path meets the walk would block the way in: move it along the road.
        BlockPos at = door.relative(front, reach);
        RoadEditor.clearDecorationFor(level, at.getX(), at.getZ(), front.getClockWise());
        // Standing height per column: the floor's for as long as possible, then one block a step to the road's.
        int last = reach - 1, target = road.y() + 1;
        int[] h = new int[reach + 1];
        h[0] = door.getY();
        h[reach] = target;
        for (int n = 1; n <= last; n++) h[n] = Math.clamp(door.getY(), target - (last - n), target + (last - n));
        BlockState path = Blocks.DIRT_PATH.defaultBlockState(), dirt = Blocks.DIRT.defaultBlockState(), air = Blocks.AIR.defaultBlockState();
        for (int n = 1; n <= last; n++) {
            BlockPos c = door.relative(front, n);
            int floor = h[n] - 1;
            // Headroom: clear earth, plants, leaves and the garden's own walk and gate where the path dips (never
            // anything else built).
            for (int y = h[n]; y < h[n] + 3; y++) {
                BlockPos q = new BlockPos(c.getX(), y, c.getZ());
                BlockState s = level.getBlockState(q);
                if (!s.isAir() && (natural(s) || s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(Blocks.DIRT_PATH) || s.is(BlockTags.FENCE_GATES))) {
                    level.setBlock(q, air, Block.UPDATE_ALL);
                }
            }
            // Bank it up from solid ground.
            for (int y = floor - 1; y > floor - 10; y--) {
                BlockPos q = new BlockPos(c.getX(), y, c.getZ());
                BlockState s = level.getBlockState(q);
                if (!(s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced())) break;
                level.setBlock(q, dirt, Block.UPDATE_ALL);
            }
            BlockPos q = new BlockPos(c.getX(), floor, c.getZ());
            BlockState s = level.getBlockState(q);
            if (!(s.isAir() || natural(s) || s.canBeReplaced() || s.is(Blocks.DIRT_PATH) || s.is(Blocks.COBBLESTONE_STAIRS))) continue;
            // The higher of two columns gets a stair facing up the step; the rest is a beaten path.
            Direction up = h[n] > h[n - 1] ? front : h[n] > h[n + 1] ? front.getOpposite() : null;
            level.setBlock(q, up == null ? path : Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, up), Block.UPDATE_ALL);
        }
    }

    /** Digs out a quarry's pit before its first block goes in; the stone goes to the storehouse. */
    private static void dig(ServerLevel level, City city, BuildingGenerator.Plan plan) {
        int stone = 0;
        for (BlockPos p : plan.dig()) {
            BlockState s = level.getBlockState(p);
            if (s.isAir() || s.hasBlockEntity()) continue;
            if (s.is(BlockTags.BASE_STONE_OVERWORLD)) stone++;
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        int n = stone;
        if (n > 0) CityManager.get(level.getServer()).editStockQuiet(city, stock -> Stock.add(stock, net.minecraft.world.item.Items.COBBLESTONE, n));
    }

    /** Road columns inside a lot (its edge rows may be road): yard work there is skipped. */
    private static Set<Long> siteRoads(ServerLevel level, Building b) {
        return SITE_ROADS.computeIfAbsent(b.id, id -> {
            Map<Long, RoadRaster.Cell> roads = ZoneRules.roadColumns(RoadNetwork.get(level), b.city);
            Set<Long> out = new HashSet<>();
            for (long cell : b.cellKeys()) {
                for (int x = RoadRaster.keyX(cell) * 8; x < RoadRaster.keyX(cell) * 8 + 8; x++) {
                    for (int z = RoadRaster.keyZ(cell) * 8; z < RoadRaster.keyZ(cell) * 8 + 8; z++) {
                        if (roads.containsKey(RoadRaster.key(x, z))) out.add(RoadRaster.key(x, z));
                    }
                }
            }
            return out;
        });
    }

    // ---------------------------------------------------------------- construction

    private static boolean loaded(ServerLevel level, BuildingGenerator.Plan plan) {
        return level.hasChunkAt(plan.min()) && level.hasChunkAt(plan.max())
                && level.hasChunkAt(new BlockPos(plan.min().getX(), 0, plan.max().getZ())) && level.hasChunkAt(new BlockPos(plan.max().getX(), 0, plan.min().getZ()));
    }

    /** Places the next step of a site. Returns false when nothing more can happen this tick. */
    private static boolean build(ServerLevel level, City city, LandData land, Building b, boolean catchingUp) {
        BuildingGenerator.Plan plan = plan(b);
        if (!catchingUp && !loaded(level, plan)) {
            if (b.owed < plan.steps().size() - b.progress) { b.owed++; land.setDirty(); }
            return false;
        }
        if (b.progress >= plan.steps().size()) {
            finish(level, city, b, plan);
            return false;
        }
        if (b.progress == 0 && !plan.dig().isEmpty()) dig(level, city, plan);
        BuildingGenerator.Step step = plan.steps().get(b.progress);
        if (step.pos().getY() <= b.baseY && siteRoads(level, b).contains(RoadRaster.key(step.pos().getX(), step.pos().getZ()))) {
            b.progress++; // yard work that would land on the road
            return true;
        }
        BlockState here = level.getBlockState(step.pos());
        if (here.is(step.state().getBlock())) { // panes, fences and stairs reshape to their neighbours
            b.progress++;
            land.setDirty();
            return true;
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
                    tell(level, city, Component.translatable("building.townstead.paused", kindName(plan), cost.displayName()), false);
                }
                return false;
            }
            CityManager.get(level.getServer()).editStockQuiet(city, stock -> Stock.consume(stock, Map.of(cost, 1)));
        }
        if (!catchingUp) raiseScaffold(level, b, plan, step.pos().getY());
        if (step.state().getBlock() instanceof DoorBlock) {
            level.setBlock(step.pos().above(), step.state().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        // Connect to what's already there (fences, panes, stair corners); keep the planned state if that would drop it.
        BlockState placed = step.state();
        if (!(placed.getBlock() instanceof DoorBlock)) {
            BlockState shaped = Block.updateFromNeighbourShapes(placed, level, step.pos());
            if (shaped.is(placed.getBlock())) placed = shaped;
        }
        level.setBlock(step.pos(), placed, Block.UPDATE_ALL);
        if (!catchingUp) {
            SoundType sound = step.state().getSoundType();
            level.playSound(null, step.pos(), sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1f) / 4f, sound.getPitch() * 0.8f);
            if (b.progress % 3 == 0) {
                level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, step.state()), step.pos().getX() + 0.5, step.pos().getY() + 0.5,
                        step.pos().getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.05);
            }
        }
        b.progress++;
        if (b.state == Building.State.PAUSED) {
            b.state = Building.State.CONSTRUCTING;
            b.needs = null;
        }
        land.setDirty();
        if (b.progress % 8 == 0) markDirty();
        return true;
    }

    /** Sites whose chunks were unloaded: load them briefly and place everything owed since. */
    private static void catchUp(ServerLevel level, CityManager cities, LandData land) {
        for (Building b : List.copyOf(land.buildings())) {
            if (b.owed <= 0 || b.state == Building.State.ACTIVE || b.state == Building.State.ABANDONED) continue;
            City city = cities.byId(b.city).orElse(null);
            if (city == null) continue;
            BuildingGenerator.Plan plan = plan(b);
            for (int x = plan.min().getX() >> 4; x <= plan.max().getX() >> 4; x++) {
                for (int z = plan.min().getZ() >> 4; z <= plan.max().getZ() >> 4; z++) level.getChunk(x, z);
            }
            int owed = b.owed;
            b.owed = 0;
            for (int i = 0; i < owed; i++) if (!build(level, city, land, b, true)) break;
            if (b.progress >= plan.steps().size() && b.state != Building.State.ACTIVE) finish(level, city, b, plan);
            markDirty();
        }
    }

    /** Scaffolding beside the walls tracks the height being built. */
    private static void raiseScaffold(ServerLevel level, Building b, BuildingGenerator.Plan plan, int y) {
        Set<BlockPos> planned = planned(b);
        for (BlockPos base : plan.scaffold()) {
            for (int sy = base.getY(); sy <= Math.min(y + 1, base.getY() + 24); sy++) {
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
            for (int sy = base.getY() + 26; sy >= base.getY(); sy--) {
                BlockPos p = new BlockPos(base.getX(), sy, base.getZ());
                if (level.getBlockState(p).is(Blocks.SCAFFOLDING)) {
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
    }

    private static void finish(ServerLevel level, City city, Building b, BuildingGenerator.Plan plan) {
        removeScaffold(level, plan);
        frontPath(level, b, plan, ZoneRules.roadColumns(RoadNetwork.get(level), b.city));
        boolean upgrade = b.upgrading && !b.repairing, repaired = b.repairing;
        b.state = Building.State.ACTIVE;
        b.needs = null;
        b.upgrading = false;
        b.repairing = false;
        b.owed = 0;
        b.households = plan.households();
        b.jobs = plan.jobs();
        b.activeSince = level.getGameTime();
        LandData.get(level).setDirty();
        markDirty();
        BlockPos door = plan.steps().getFirst().pos();
        level.playSound(null, door, upgrade ? SoundEvents.PLAYER_LEVELUP : SoundEvents.VILLAGER_CELEBRATE, SoundSource.NEUTRAL, 0.8f, 1.0f);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, (plan.min().getX() + plan.max().getX()) / 2.0 + 0.5, plan.max().getY() + 1,
                (plan.min().getZ() + plan.max().getZ()) / 2.0 + 0.5, 20, 2, 1, 2, 0.02);
        Component kind = kindName(plan);
        if (repaired) tell(level, city, Component.translatable("building.townstead.repaired", kind), true);
        else if (upgrade) tell(level, city, Component.translatable("building.townstead.levelled", kind, b.level), true);
        else if (b.civic()) tell(level, city, Component.translatable(switch (b.serviceType().kind) {
                case POWER -> "service.townstead.opened_power";
                case WATER -> "service.townstead.opened_water";
                case SERVICE -> "service.townstead.opened_service";
                case TRANSIT -> "service.townstead.opened_transit";
            }, kind, b.serviceType().kind == com.cloudkingtv.townstead.service.ServiceType.Kind.SERVICE ? b.serviceType().radius : b.serviceType().capacity), true);
        else tell(level, city, b.zone.residential()
                ? Component.translatable("building.townstead.moved_in", kind, Residents.people(b))
                : Component.translatable("building.townstead.opened", kind, b.jobs), true);
        BlockPos where = BlockPos.containing(b.centerX(), b.baseY, b.centerZ());
        if (repaired) com.cloudkingtv.townstead.progress.Chirps.post(level, city, "repaired", where, false, kind);
        else if (upgrade) com.cloudkingtv.townstead.progress.Chirps.post(level, city, "levelled", where, false, kind, b.level);
        else if (b.civic()) com.cloudkingtv.townstead.progress.Chirps.post(level, city, b.serviceType().landmark() ? "landmark" : "service", where,
                b.serviceType().landmark(), kind, city.name());
        else com.cloudkingtv.townstead.progress.Chirps.post(level, city, b.zone.residential() ? "moved_in" : "opened", where, false, kind);
    }

    // ---------------------------------------------------------------- levels and trouble

    private static void review(ServerLevel level, City city, LandData land) {
        List<Building> mine = land.buildingsOf(city.id());
        if (mine.isEmpty()) return;
        com.cloudkingtv.townstead.sim.TownModel model = com.cloudkingtv.townstead.economy.TownEconomy.model(level, city);
        double speed = speed();
        long upgrading = mine.stream().filter(b -> b.upgrading).count();
        com.cloudkingtv.townstead.disaster.Fires.review(level, city, mine, model, REVIEW_INTERVAL * speed);
        // One building a round has its front path looked over, in case a tree, a boulder or the weather settled on it.
        Building walk = mine.get((int) Math.floorMod(level.getGameTime() / REVIEW_INTERVAL, (long) mine.size()));
        if (walk.state == Building.State.ACTIVE && loaded(level, plan(walk))) {
            frontPath(level, walk, plan(walk), ZoneRules.roadColumns(RoadNetwork.get(level), city.id()));
        }
        for (Building b : mine) {
            boolean built = b.state == Building.State.ACTIVE || b.state == Building.State.ABANDONED;
            if (!built) continue;
            Component trouble = Progression.trouble(b, model);
            int before = b.trouble;
            // Capped, so recovery takes the same time however long the trouble lasted.
            if (trouble != null) b.trouble = Math.min(Progression.ABANDON_AFTER, b.trouble + (int) Math.round(REVIEW_INTERVAL * speed));
            else b.trouble = Math.max(0, b.trouble - (int) Math.round(REVIEW_INTERVAL * 2 * speed));
            if (b.trouble != before) { land.setDirty(); markDirty(); }
            if (before == 0 && b.trouble > 0 && trouble != null && b.state == Building.State.ACTIVE) {
                com.cloudkingtv.townstead.progress.Chirps.post(level, city, "trouble", BlockPos.containing(b.centerX(), b.baseY, b.centerZ()), false,
                        kindName(BuildingGenerator.plan(b)), trouble);
            }
            if (b.state == Building.State.ACTIVE && b.trouble >= Progression.ABANDON_AFTER) {
                abandon(level, city, b, trouble);
                continue;
            }
            if (b.state == Building.State.ABANDONED && b.trouble == 0) {
                repair(level, city, b);
                continue;
            }
            if (b.state == Building.State.ACTIVE && b.fire == 0 && trouble == null && upgrading < MAX_UPGRADES
                    && Progression.canLevel(Progression.nextLevel(b, model, level.getGameTime(), speed))) {
                upgrade(level, city, b);
                upgrading++;
            }
        }
    }

    /** Rebuild at the next level: take down what the new plan doesn't keep (salvaged to the storehouse), then build. */
    private static void upgrade(ServerLevel level, City city, Building b) {
        BuildingGenerator.Plan old = plan(b);
        b.level++;
        BuildingGenerator.Plan next = plan(b);
        Set<BlockPos> keep = next.positions();
        Map<Material, Integer> salvage = new java.util.EnumMap<>(Material.class);
        for (int i = old.steps().size() - 1; i >= 0; i--) {
            BuildingGenerator.Step s = old.steps().get(i);
            if (keep.contains(s.pos()) || !level.getBlockState(s.pos()).is(s.state().getBlock())) continue;
            Material m = BuildCost.of(s.state());
            if (m != null) salvage.merge(m, 1, Integer::sum);
            if (s.state().getBlock() instanceof DoorBlock) level.setBlock(s.pos().above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            level.setBlock(s.pos(), s.pos().getY() < b.baseY ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        if (!salvage.isEmpty() && !isFree(level, city)) {
            CityManager.get(level.getServer()).editStockQuiet(city, stock -> salvage.forEach((m, n) -> Stock.add(stock, m.icon().getItem(), n)));
        }
        b.upgrading = true;
        b.progress = 0;
        b.state = Building.State.CONSTRUCTING;
        LandData.get(level).setDirty();
        markDirty();
        tell(level, city, Component.translatable("building.townstead.levelling", kindName(next), b.level), true);
    }

    /** Boarded windows, no door, cobwebs: the building stands empty until things improve. */
    private static void abandon(ServerLevel level, City city, Building b, @Nullable Component why) {
        BuildingGenerator.Plan plan = plan(b);
        int i = 0;
        for (BuildingGenerator.Step s : plan.steps()) {
            BlockState here = level.getBlockState(s.pos());
            if (s.state().is(Blocks.GLASS_PANE) && here.is(Blocks.GLASS_PANE)) {
                level.setBlock(s.pos(), (i++ % 3 == 0 ? Blocks.COBWEB : Blocks.SPRUCE_PLANKS).defaultBlockState(), Block.UPDATE_ALL);
            } else if ((s.state().is(Blocks.LANTERN) || s.state().is(Blocks.WALL_TORCH)) && here.is(s.state().getBlock())) {
                level.setBlock(s.pos(), Blocks.COBWEB.defaultBlockState(), Block.UPDATE_ALL);
            } else if (s.state().getBlock() instanceof DoorBlock && here.getBlock() instanceof DoorBlock) {
                level.setBlock(s.pos().above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                level.setBlock(s.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
        b.state = Building.State.ABANDONED;
        LandData.get(level).setDirty();
        markDirty();
        level.playSound(null, plan.min(), SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, 0.8f, 0.6f);
        tell(level, city, Component.translatable("building.townstead.abandoned", kindName(plan), why == null ? Component.empty() : why), false);
        com.cloudkingtv.townstead.progress.Chirps.post(level, city, "abandoned", BlockPos.containing(b.centerX(), b.baseY, b.centerZ()), true,
                kindName(plan), why == null ? Component.empty() : why);
    }

    /** People come back: clear the cobwebs and boards and rebuild what's missing. */
    private static void repair(ServerLevel level, City city, Building b) {
        BuildingGenerator.Plan plan = plan(b);
        for (BuildingGenerator.Step s : plan.steps()) {
            if (level.getBlockState(s.pos()).is(Blocks.COBWEB)) level.setBlock(s.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        b.state = Building.State.CONSTRUCTING;
        b.progress = 0;
        b.upgrading = true; // keeps counting as lived-in while the repairs go on
        b.repairing = true;
        b.burned = false;
        LandData.get(level).setDirty();
        markDirty();
        tell(level, city, Component.translatable("building.townstead.repairing", kindName(plan)), true);
    }

    static boolean isFree(ServerLevel level, City city) {
        if (TownsteadConfig.FREE_BUILD.get()) return true;
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(city.owner());
        return owner != null && owner.getAbilities().instabuild;
    }

    private static void tell(ServerLevel level, City city, Component message, boolean ok) {
        for (ServerPlayer mayor : city.mayorsOnline(level.getServer())) RoadEditor.notify(mayor, message, ok);
    }

    private TownGrowth() {}
}
