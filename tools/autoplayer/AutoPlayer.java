package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.LandEditor;
import com.cloudkingtv.townstead.building.Residents;
import com.cloudkingtv.townstead.building.TownGrowth;
import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.city.Stock;
import com.cloudkingtv.townstead.city.TilePos;
import com.cloudkingtv.townstead.city.TownActions;
import com.cloudkingtv.townstead.district.DistrictEditor;
import com.cloudkingtv.townstead.district.Specialization;
import com.cloudkingtv.townstead.economy.Budget;
import com.cloudkingtv.townstead.economy.Finance;
import com.cloudkingtv.townstead.economy.Loan;
import com.cloudkingtv.townstead.economy.TownEconomy;
import com.cloudkingtv.townstead.industry.Industry;
import com.cloudkingtv.townstead.network.TransitPayloads;
import com.cloudkingtv.townstead.progress.Milestone;
import com.cloudkingtv.townstead.registry.TownsteadRegistry;
import com.cloudkingtv.townstead.road.Material;
import com.cloudkingtv.townstead.road.RoadCurve;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadType;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.sim.TownModel;
import com.cloudkingtv.townstead.transit.CoachLines;
import com.cloudkingtv.townstead.zone.LandData;
import com.cloudkingtv.townstead.zone.ZoneRules;
import com.cloudkingtv.townstead.zone.ZoneType;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Scratch-only test harness for Phase 9: plays a town from founding the way a person would, through the same calls
 * the planner's payloads make, in survival (construction and roads cost materials). It brings one starter kit of
 * hand-gathered materials, then only buys in what the town can't make. Every so often it looks at the town and
 * does what a mayor would: lay the next street when a zone has nowhere to grow, zone by demand, put services where
 * homes lack them, add power and water before they run out, buy land, specialise industry, run coach lines when
 * the roads jam, and keep the books. One CSV row per in-game day goes to run/autoplay/days.csv.
 * Runs when run/autoplay.flag exists; -Dtownstead.autoplay.speed sets the growth speed (default 20).
 */
@EventBusSubscriber(modid = "townstead", value = Dist.CLIENT)
public final class AutoPlayer {
    static final boolean ON = new File("autoplay.flag").exists();
    static final double SPEED = Double.parseDouble(System.getProperty("townstead.autoplay.speed", "20"));
    /** Stop after this many speed-1 hours of play. */
    static final double MAX_HOURS = Double.parseDouble(System.getProperty("townstead.autoplay.hours", "24"));
    static final int THINK = 100, DAY = (int) Math.round(24000 / SPEED);

    static int t, foundAt = -1, nextDay = -1;
    static volatile boolean founded, done;
    static BlockPos centre, hall;
    static int hcx, hcz;
    static long startTick;
    static PrintWriter days, events;
    static long deposited, importedCents;
    static int lastMilestone, stuckDays, lastPop, day;
    static final Set<String> builtEdges = new HashSet<>();
    static final Map<String, Integer> failedEdges = new HashMap<>();
    static final Map<Material, Long> lastImport = new EnumMap<>(Material.class);
    static int forestry = -1, quarry = -1, farming = -1;

    static void log(String s) {
        String line = String.format("[Auto] h=%.2f %s", hours(), s);
        System.out.println(line);
        if (events != null) { events.println(line); events.flush(); }
    }

    static double hours() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || !founded) return 0;
        return (server.getTickCount() - startTick) * SPEED / 72000.0;
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) {
        if (!ON || done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        t++;
        MinecraftServer server = mc.getSingleplayerServer();
        if (t == 40) server.execute(() -> find(server));
        if (foundAt > 0 && t == foundAt + 200) server.execute(() -> found(server));
        if (!founded) return;
        if (t % THINK == 0) server.execute(() -> { try { think(server); } catch (Throwable ex) { log("think failed: " + ex); ex.printStackTrace(System.out); } });
        if (t >= nextDay) {
            nextDay = t + DAY;
            server.execute(() -> { try { record(server); } catch (Throwable ex) { log("record failed: " + ex); ex.printStackTrace(System.out); } });
        }
    }

    // ---------------------------------------------------------------- founding

    /** Flat, dry ground near spawn, judged from the world generator (no chunks made). */
    static void find(MinecraftServer server) {
        var cmd = server.getCommands();
        var src = server.createCommandSourceStack();
        for (String c : List.of("time set noon", "gamerule advance_time false", "gamerule advance_weather false", "weather clear", "gamemode survival @a"))
            cmd.performPrefixedCommand(src, c);
        TownsteadConfig.GROWTH_SPEED.set(SPEED);
        TownsteadConfig.FREE_BUILD.set(false);
        TownsteadConfig.UNLOCK_ALL.set(false);
        TownsteadConfig.FIRES.set(true);
        ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
        p.getAbilities().mayfly = true;
        p.getAbilities().flying = true;
        p.getAbilities().invulnerable = true;
        p.onUpdateAbilities();
        ServerLevel level = p.level();
        var gen = level.getChunkSource().getGenerator();
        var rs = level.getChunkSource().randomState();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int cx = -14; cx <= 14; cx++) for (int cz = -14; cz <= 14; cz++) {
            int x0 = cx * 64, z0 = cz * 64;
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, wet = 0, n = 0;
            for (int dx = -160; dx <= 160; dx += 16) for (int dz = -160; dz <= 160; dz += 16) {
                int h = gen.getBaseHeight(x0 + 32 + dx, z0 + 32 + dz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                int floor = gen.getBaseHeight(x0 + 32 + dx, z0 + 32 + dz, Heightmap.Types.OCEAN_FLOOR_WG, level, rs);
                if (h - floor > 0) wet++;
                min = Math.min(min, h);
                max = Math.max(max, h);
                n++;
            }
            if (wet * 20 > n) continue;
            double score = (max - min) * 30 + Math.hypot(x0, z0) * 0.2;
            if (score < bestScore) { bestScore = score; best = new BlockPos(x0 + 32, 0, z0 + 32); }
        }
        if (best == null) best = new BlockPos(32, 0, 32);
        centre = best;
        p.teleportTo(level, best.getX() + 0.5, 150, best.getZ() + 0.5, Set.of(), 0f, 60f, true);
        foundAt = t;
        System.out.println("[Auto] chose " + best + " score " + bestScore);
    }

    static void found(MinecraftServer server) {
        ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
        ServerLevel level = p.level();
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centre.getX(), centre.getZ());
        hall = new BlockPos(centre.getX(), y, centre.getZ());
        BlockState state = TownsteadRegistry.TOWN_HALL.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH);
        level.setBlock(hall, state, 3);
        state.getBlock().setPlacedBy(level, hall, state, p, TownsteadRegistry.TOWN_HALL_ITEM.get().getDefaultInstance());
        City city = city(server);
        hcx = Math.floorDiv(hall.getX(), 8);
        hcz = Math.floorDiv(hall.getZ(), 8);
        // A Town Storehouse beside the hall, and the starter kit: about a day of gathering by hand.
        BlockPos store = hall.east(2);
        BlockState sh = TownsteadRegistry.TOWN_STOREHOUSE.get().defaultBlockState();
        level.setBlock(store, sh, 3);
        sh.getBlock().setPlacedBy(level, store, sh, p, TownsteadRegistry.TOWN_STOREHOUSE_ITEM.get().getDefaultInstance());
        Map<Material, Integer> kit = new EnumMap<>(Material.class);
        kit.put(Material.LOGS, 256); kit.put(Material.PLANKS, 512); kit.put(Material.COBBLESTONE, 512); kit.put(Material.STONE_BRICKS, 256);
        kit.put(Material.DIRT, 256); kit.put(Material.GLASS, 128); kit.put(Material.FENCES, 64); kit.put(Material.LANTERNS, 32);
        kit.put(Material.LEAVES, 64); kit.put(Material.WOOL, 64); kit.put(Material.WORKSTATIONS, 32); kit.put(Material.HAY, 32); kit.put(Material.BRICKS, 64);
        CityManager.get(server).editStock(server, city, st -> kit.forEach((m, n) -> Stock.add(st, m.item(), n)));
        deposited = kit.values().stream().mapToInt(Integer::intValue).sum();
        KIT = deposited;
        new File("autoplay").mkdirs();
        try {
            days = new PrintWriter(new FileWriter("autoplay/days.csv"));
            events = new PrintWriter(new FileWriter("autoplay/events.log"));
        } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
        days.println("day,hours,people,households,jobs,buildings,active,sites,paused,abandoned,milestone,tiles,treasury,income,expenses,net,exports,"
                + "tax_home,tax_shop,tax_work,tax_office,happiness,unemployment,R,C,I,O,flow,trips,riders,stock,made,starved,imported,deposited,segments,services,mspt");
        days.flush();
        startTick = server.getTickCount();
        founded = true;
        nextDay = t + 1;
        // What typical buildings cost (units of material, blocks placed), for tuning.
        record Probe(ZoneType zone, String service, String spec, int level, int wide, int deep) {}
        for (Probe pr : List.of(new Probe(ZoneType.RESIDENTIAL, "", "", 1, 1, 1), new Probe(ZoneType.RESIDENTIAL, "", "", 2, 1, 1),
                new Probe(ZoneType.RESIDENTIAL, "", "", 3, 2, 1), new Probe(ZoneType.RESIDENTIAL_HIGH, "", "", 2, 2, 1),
                new Probe(ZoneType.COMMERCIAL, "", "", 1, 1, 1), new Probe(ZoneType.COMMERCIAL_HIGH, "", "", 2, 2, 1),
                new Probe(ZoneType.INDUSTRIAL, "", "", 1, 2, 1), new Probe(ZoneType.INDUSTRIAL, "", "forestry", 1, 2, 2),
                new Probe(ZoneType.INDUSTRIAL, "", "quarry", 1, 2, 2), new Probe(ZoneType.OFFICE, "", "", 2, 2, 1),
                new Probe(ZoneType.CIVIC, "windmill", "", 1, 1, 1), new Probe(ZoneType.CIVIC, "clinic", "", 1, 2, 1))) {
            Building b = new Building(0, city.id(), pr.zone(), hcx + 20, hcz + 20, Direction.SOUTH, hall.getY(), 12345L + pr.level() * 7 + pr.wide(), 0, 0);
            b.service = pr.service(); b.spec = pr.spec(); b.level = pr.level(); b.wide = pr.wide(); b.deep = pr.deep();
            if (b.civic()) { b.wide = b.serviceType().wide; b.deep = b.serviceType().deep; }
            var plan = com.cloudkingtv.townstead.building.BuildingGenerator.plan(b);
            int units = TownGrowth.bill(plan).values().stream().mapToInt(Integer::intValue).sum();
            System.out.println("[Auto] cost " + plan.kind() + " L" + pr.level() + " " + b.wide + "x" + b.deep + ": " + units + " units, "
                    + plan.steps().size() + " blocks, " + plan.households() + " households, " + plan.jobs() + " jobs " + TownGrowth.bill(plan));
        }
        log("founded " + city.name() + " hall " + hall + " cell " + hcx + "," + hcz + " tiles " + city.tiles().size() + " kit " + deposited);
    }

    static City city(MinecraftServer server) {
        return CityManager.get(server).ownedBy(server.getPlayerList().getPlayers().getFirst().getUUID()).orElseThrow();
    }

    // ---------------------------------------------------------------- the street grid

    /** East-west streets every 5 cells, north-south every 6, offset so the hall sits in a block of its own. */
    static boolean hLine(int rz) { return Math.floorMod(rz - 2, 5) == 0; }
    static boolean vLine(int rx) { return Math.floorMod(rx - 3, 6) == 0; }
    static double bx(int rx) { return (hcx + rx) * 8 + 4.5; }
    static double bz(int rz) { return (hcz + rz) * 8 + 4.5; }

    /** Every grid edge inside the town's land, nearest the hall first: {horizontal?1:0, line, from, to}. */
    static List<int[]> edges(City city) {
        List<int[]> out = new ArrayList<>();
        for (int rz = -60; rz <= 60; rz++) {
            if (!hLine(rz)) continue;
            for (int rx = -60; rx <= 60; rx++) if (vLine(rx) && inLand(city, rx, rz, rx + 6, rz)) out.add(new int[] {1, rz, rx, rx + 6});
        }
        for (int rx = -60; rx <= 60; rx++) {
            if (!vLine(rx)) continue;
            for (int rz = -60; rz <= 60; rz++) if (hLine(rz) && inLand(city, rx, rz, rx, rz + 5)) out.add(new int[] {0, rx, rz, rz + 5});
        }
        out.sort((a, b) -> Double.compare(dist(a), dist(b)));
        return out;
    }

    static double dist(int[] e) {
        double mx = e[0] == 1 ? (e[2] + e[3]) / 2.0 : e[1], mz = e[0] == 1 ? e[1] : (e[2] + e[3]) / 2.0;
        return Math.hypot(mx, mz);
    }

    static String key(int[] e) { return e[0] + ":" + e[1] + ":" + e[2] + ":" + e[3]; }

    static boolean inLand(City city, int rx0, int rz0, int rx1, int rz1) {
        for (int k = 0; k <= 8; k++) {
            double x = bx(rx0) + (bx(rx1) - bx(rx0)) * k / 8.0, z = bz(rz0) + (bz(rz1) - bz(rz0)) * k / 8.0;
            if (!city.tiles().contains(TilePos.ofBlock((int) Math.floor(x), (int) Math.floor(z)))) return false;
        }
        return true;
    }

    /** Lays one grid edge (loading its chunks first); buys road materials in when it can't be paid for. */
    static boolean lay(ServerPlayer p, City city, int[] e) {
        ServerLevel level = p.level();
        double ax = e[0] == 1 ? bx(e[2]) : bx(e[1]), az = e[0] == 1 ? bz(e[1]) : bz(e[2]);
        double bx = e[0] == 1 ? bx(e[3]) : bx(e[1]), bz = e[0] == 1 ? bz(e[1]) : bz(e[3]);
        for (int k = 0; k <= 8; k++) level.getChunk(((int) Math.floor(ax + (bx - ax) * k / 8)) >> 4, ((int) Math.floor(az + (bz - az) * k / 8)) >> 4);
        // The main streets through the hall's block become avenues once the town may build them.
        boolean main = (e[0] == 1 && e[1] == 2) || (e[0] == 0 && e[1] == 3);
        RoadType type = main && Milestone.unlocked(city.milestone(), RoadType.AVENUE) ? RoadType.AVENUE : RoadType.STREET;
        int before = RoadNetwork.get(level).segments().size();
        RoadEditor.build(p, city, type, RoadCurve.straight(ax, az, bx, bz));
        // Can't afford a street: a lane (no sidewalks) does for now, as a person short of stone would lay.
        if (false && RoadNetwork.get(level).segments().size() == before && type == RoadType.STREET) { // lanes jam and can't be widened later
            type = RoadType.LANE;
            RoadEditor.build(p, city, type, RoadCurve.straight(ax, az, bx, bz));
        }
        if (RoadNetwork.get(level).segments().size() != before) {
            builtEdges.add(key(e));
            log("road " + type + " " + key(e));
            return true;
        }
        // Probably short of materials: top up what roads use (fill dirt comes by the hundred on rough ground).
        Map<Material, Integer> want = Map.of(Material.DIRT, 448, Material.STONE_BRICKS, 192, Material.COBBLESTONE, 128,
                Material.LANTERNS, 32, Material.FENCES, 64, Material.LEAVES, 64);
        want.forEach((mat, n) -> { int have = Stock.count(city.stock(), mat); if (have < n) supply(p, city, mat, n - have, "road"); });
        failedEdges.merge(key(e), 1, Integer::sum);
        log("road refused " + key(e) + " (try " + failedEdges.get(key(e)) + ")");
        return false;
    }

    // ---------------------------------------------------------------- the mayor's round

    static void think(MinecraftServer server) {
        if (done) return;
        ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
        ServerLevel level = p.level();
        City city = city(server);
        if (city.milestone() != lastMilestone) {
            for (int i = lastMilestone + 1; i <= city.milestone(); i++) log("MILESTONE " + Milestone.of(i).key() + " (" + Milestone.of(i).population + ")");
            lastMilestone = city.milestone();
        }
        if (hours() >= MAX_HOURS || city.milestone() >= Milestone.MEGALOPOLIS.ordinal()) { finish(server, city); return; }
        TownModel m = TownEconomy.model(level, city);
        LandData land = LandData.get(level);
        List<Building> town = land.buildingsOf(city.id());
        if (builtEdges.isEmpty()) {
            // The first streets: round the hall's block.
            for (int[] e : edges(city)) if (dist(e) < 6) lay(p, city, e);
            return;
        }
        imports(p, city, town);
        utilities(p, city, m, town);
        services(p, city, m, town);
        landmarks(p, city, town);
        specialise(p, city);
        zoning(p, city, m, land, town);
        transit(p, city, m, town);
        // (Widening lanes doesn't work once buildings line them: no upgrades, and no lanes laid.)
    }

    /** Blocks a mayor gathers by hand per speed-1 hour while the town is small (none from Big Town on). */
    static final int GATHER_PER_HOUR = 1500;

    /**
     * Sites stuck on a material: while the town is small the mayor gathers some by hand (up to an hourly budget),
     * otherwise buys a stack in if the purse allows.
     */
    static void imports(ServerPlayer p, City city, List<Building> town) {
        Map<Material, Integer> wanted = new EnumMap<>(Material.class);
        for (Building b : town) if (b.state == Building.State.PAUSED && b.needs != null) wanted.merge(b.needs, 1, Integer::sum);
        wanted.forEach((mat, n) -> supply(p, city, mat, 64 * Math.min(4, n), "site"));
    }

    /** Gets {@code n} of a material into the storehouse: by hand while the town is small and the hour's budget lasts, else bought in. */
    static void supply(ServerPlayer p, City city, Material mat, int n, String why) {
        for (int left = n; left > 0; left -= 64) {
            if (city.milestone() < Milestone.BIG_TOWN.ordinal() && deposited - KIT < 1000 + hours() * GATHER_PER_HOUR) {
                CityManager.get(p.level().getServer()).editStock(p.level().getServer(), city, st -> Stock.add(st, mat.item(), 64));
                deposited += 64;
                gatherLog += 64;
            } else {
                lastImport.remove(mat);
                long before = city.treasury();
                importStack(p, city, mat, why);
                if (city.treasury() == before) break; // can't afford it
            }
        }
        if (gatherLog >= 640) { log("gathered by hand: " + (deposited - KIT) + " so far"); gatherLog = 0; }
    }

    static long KIT, gatherLog;

    static void importStack(ServerPlayer p, City city, Material mat, String why) {
        long now = p.level().getGameTime();
        if (now - lastImport.getOrDefault(mat, -100000L) < 400) return; // give a delivery time to land
        long cost = 64L * mat.importPrice();
        // Keep a reserve, and save up for land while growth waits on it.
        long reserve = 20 * TownEconomy.EMERALD + (p.level().getGameTime() - landWanted < 2 * DAY
                ? TownActions.tilePrice(city.tiles().size()) * TownEconomy.EMERALD : 0);
        if (city.treasury() - cost < reserve) return;
        long before = city.treasury();
        TownEconomy.buy(p, city, mat.ordinal(), 64);
        if (city.treasury() < before) {
            importedCents += before - city.treasury();
            lastImport.put(mat, now);
            log("import 64 " + mat + " for " + why + " (" + Budget.format(before - city.treasury()) + ")");
        }
    }

    /** Power and water ahead of need: more when anything goes without, or the networks run over 80%. */
    static void utilities(ServerPlayer p, City city, TownModel m, List<Building> town) {
        if (m.broke()) return; // in debt everything runs short; more plants would only deepen it
        int pc = 0, pu = 0, wc = 0, wu = 0, noPower = 0, noWater = 0;
        for (var g : m.util.grids().values()) { pc += g.powerCapacity(); pu += g.powerUsed(); wc += g.waterCapacity(); wu += g.waterUsed(); }
        for (Building b : town) {
            if (b.civic() || !b.working()) continue;
            if (!m.util.power(b)) noPower++;
            if (!m.util.water(b)) noWater++;
        }
        boolean powerBuilding = town.stream().anyMatch(b -> b.civic() && b.serviceType().kind == ServiceType.Kind.POWER && b.state != Building.State.ACTIVE);
        boolean waterBuilding = town.stream().anyMatch(b -> b.civic() && b.serviceType().kind == ServiceType.Kind.WATER && b.state != Building.State.ACTIVE);
        if (!powerBuilding && (pc == 0 || noPower > 0 || pu > pc * 0.8)) {
            int[] at = without(m, town, true, 12, -2);
            if (Milestone.unlocked(city.milestone(), ServiceType.WATERMILL) && place(p, city, ServiceType.WATERMILL, at[0], at[1], true) != null) return;
            place(p, city, ServiceType.WINDMILL, at[0], at[1], false);
        }
        if (!waterBuilding && (wc == 0 || noWater > 0 || wu > wc * 0.8)) {
            ServiceType s = Milestone.unlocked(city.milestone(), ServiceType.WATER_TOWER) ? ServiceType.WATER_TOWER : ServiceType.WELL;
            int[] at = without(m, town, false, 12, 3);
            place(p, city, s, at[0], at[1], false);
        }
    }

    /** Where the buildings going without power (or water) are, relative to the hall; else the fallback spot. */
    static int[] without(TownModel m, List<Building> town, boolean power, int fx, int fz) {
        double sx = 0, sz = 0;
        int n = 0;
        for (Building b : town) {
            if (b.civic() || !b.working() || (power ? m.util.power(b) : m.util.water(b))) continue;
            sx += b.centerX();
            sz += b.centerZ();
            n++;
        }
        if (n == 0) return new int[] {fx, fz};
        return new int[] {Math.floorDiv((int) (sx / n), 8) - hcx, Math.floorDiv((int) (sz / n), 8) - hcz};
    }

    /** A service where enough homes are out of its reach, at the middle of those homes. One a round. */
    static void services(ServerPlayer p, City city, TownModel m, List<Building> town) {
        // Only what the books can carry: the day's balance must cover the new upkeep (or the purse be deep).
        long net = Finance.daily(m).net();
        int homes = 0;
        for (Building b : town) if (b.zone.residential() && b.working()) homes++;
        if (homes < 4) return;
        if (p.level().getGameTime() - lastService < DAY) return; // one new service a day
        // The service the most homes are missing (fire stations count double once fires can start).
        ServiceType best = null;
        double bestScore = 0, bx0 = 0, bz0 = 0;
        for (ServiceType s : List.of(ServiceType.CLINIC, ServiceType.SCHOOL, ServiceType.FIRE_STATION, ServiceType.GUARD_HOUSE,
                ServiceType.COMPOST_YARD, ServiceType.PARK)) {
            if (!Milestone.unlocked(city.milestone(), s)) continue;
            if (town.stream().anyMatch(b -> b.civic() && b.serviceType() == s && b.state != Building.State.ACTIVE)) continue;
            if (net - Math.round(s.upkeep * com.cloudkingtv.townstead.Difficulty.current().costs) < 0 && city.treasury() < 300 * TownEconomy.EMERALD) continue;
            double sx = 0, sz = 0;
            int n = 0;
            for (Building b : town) {
                if (!b.zone.residential() || !b.working() || m.covered(b, s)) continue;
                sx += b.centerX();
                sz += b.centerZ();
                n++;
            }
            if (n < Math.max(3, homes / 10)) continue;
            double score = n * (s == ServiceType.FIRE_STATION ? 2 : s == ServiceType.PARK ? 0.5 : 1);
            if (score > bestScore) { bestScore = score; best = s; bx0 = sx / n; bz0 = sz / n; }
        }
        if (best == null) return;
        int rx = Math.floorDiv((int) bx0, 8) - hcx, rz = Math.floorDiv((int) bz0, 8) - hcz;
        if (place(p, city, best, rx, rz, false) != null) lastService = p.level().getGameTime();
    }

    static long lastService = -1000000;

    /** Landmarks and monuments as they unlock, near the middle of town. */
    static void landmarks(ServerPlayer p, City city, List<Building> town) {
        for (ServiceType s : ServiceType.LANDMARKS) {
            if (!Milestone.unlocked(city.milestone(), s) || town.stream().anyMatch(b -> s.key().equals(b.service))) continue;
            long need = (s.monument() ? 150 : 30) * TownEconomy.EMERALD;
            if (city.treasury() < need) continue;
            if (place(p, city, s, s.monument() ? -8 : 0, s.monument() ? 8 : 4, false) != null) return;
        }
    }

    /** Places a service on the first lot that takes it, searching outward from a cell relative to the hall. */
    static Building place(ServerPlayer p, City city, ServiceType s, int rx, int rz, boolean quiet) {
        for (int d = 0; d < 14; d++) for (int dx = -d; dx <= d; dx++) for (int dz = -d; dz <= d; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
            int cx = hcx + rx + dx, cz = hcz + rz + dz;
            if (Math.abs(cx - hcx) <= 1 && Math.abs(cz - hcz) <= 1) continue;
            if (!city.tiles().contains(TilePos.ofBlock(cx * 8, cz * 8))) continue;
            if (hLine(cz - hcz) || vLine(cx - hcx)) continue; // keep the planned streets clear
            Object res = TownGrowth.placeCivic(p.level(), city, s, cx, cz);
            if (res instanceof Building b) { log("placed " + s.key() + " #" + b.id + " at " + (cx - hcx) + "," + (cz - hcz)); return b; }
        }
        if (!quiet) log("no lot for " + s.key());
        return null;
    }

    /**
     * Industry east of rx 10: the first of it raw (forestry to the north, quarries to the south) so the town makes its
     * own logs and stone; ordinary workshops beyond rx 16 to work them up; farms beyond rx 22.
     */
    static void specialise(ServerPlayer p, City city) {
        forestry = district(p, city, forestry, 10, -60, 12, 2, Specialization.FORESTRY);
        quarry = district(p, city, quarry, 10, 3, 12, 60, Specialization.QUARRY);
        if (city.milestone() >= Milestone.TINY_TOWN.ordinal()) farming = district(p, city, farming, 22, -60, 60, 60, Specialization.FARMING);
    }

    static int district(ServerPlayer p, City city, int id, int rx0, int rz0, int rx1, int rz1, Specialization spec) {
        List<Long> cells = new ArrayList<>();
        Set<Long> have = new HashSet<>();
        city.districts().stream().filter(d -> d.id() == id).findFirst().ifPresent(d -> have.addAll(d.cells()));
        for (int rx = rx0; rx <= rx1; rx++) for (int rz = rz0; rz <= rz1; rz++) {
            int cx = hcx + rx, cz = hcz + rz;
            long k = ZoneRules.cellKey(cx, cz);
            if (city.tiles().contains(TilePos.ofBlock(cx * 8, cz * 8)) && !have.contains(k)) cells.add(k);
        }
        if (cells.isEmpty()) return id;
        if (id < 0) {
            DistrictEditor.paint(p, city, DistrictEditor.NEW, cells);
            int nid = city.districts().getLast().id();
            DistrictEditor.setSpec(p, city, nid, spec);
            log("district " + spec + " #" + nid + " cells " + cells.size());
            return nid;
        }
        DistrictEditor.paint(p, city, id, cells);
        return id;
    }

    /** Which need a cell is for: industry east, shops along the main streets, homes and offices elsewhere. */
    static ZoneType.Need sector(int rx, int rz) {
        if (rx >= 10) return ZoneType.Need.WORKSHOPS;
        if (rz == 1 || rz == 3 || rx == 2 || rx == 4) return ZoneType.Need.SHOPS;
        return ZoneType.Need.HOMES;
    }

    /**
     * Zones by demand: for each need asking for more, zone up to eight free cells in its part of town, nearest the
     * hall. Offices take home cells nearest the middle. When a need has nowhere left, the next street goes in; when
     * there's no street left to lay, the town buys land.
     */
    static void zoning(ServerPlayer p, City city, TownModel m, LandData land, List<Building> town) {
        var cols = ZoneRules.roadColumns(RoadNetwork.get(p.level()), city.id());
        Set<TilePos> tiles = city.tiles();
        for (ZoneType.Need need : List.of(ZoneType.Need.HOMES, ZoneType.Need.SHOPS, ZoneType.Need.WORKSHOPS, ZoneType.Need.OFFICES)) {
            ZoneType low = switch (need) {
                case HOMES -> ZoneType.RESIDENTIAL;
                case SHOPS -> ZoneType.COMMERCIAL;
                case WORKSHOPS -> ZoneType.INDUSTRIAL;
                default -> ZoneType.OFFICE;
            };
            if (!Milestone.unlocked(city.milestone(), low) || m.demand.plannedPeople() < low.minPopulation) continue;
            int demand = m.demand.of(need);
            if (demand < 25) continue;
            int free = 0;
            for (var e : land.zones().entrySet()) {
                if (e.getValue().city().equals(city.id()) && e.getValue().type().need == need && land.buildingAt(e.getKey()) == null
                        && buildable(p.level(), city, cols, e.getKey())) free++;
            }
            if (free >= 6) continue;
            List<long[]> spots = new ArrayList<>();
            // Preferred cells first (shops on the main streets, industry east); then, for anything but industry,
            // any free cell west of the industry.
            for (int pass = 0; pass < 2 && spots.isEmpty(); pass++) for (int rx = -60; rx <= 60; rx++) for (int rz = -60; rz <= 60; rz++) {
                if (Math.abs(rx) <= 1 && Math.abs(rz) <= 1) continue;
                if (hLine(rz) || vLine(rx)) continue; // keep the planned streets clear
                ZoneType.Need s = sector(rx, rz);
                boolean fits = pass == 0 ? (need == ZoneType.Need.OFFICES ? s == ZoneType.Need.HOMES : s == need)
                        : need != ZoneType.Need.WORKSHOPS && s != ZoneType.Need.WORKSHOPS;
                if (!fits) continue;
                int cx = hcx + rx, cz = hcz + rz;
                long k = ZoneRules.cellKey(cx, cz);
                if (land.zone(k) != null || land.buildingAt(k) != null) continue;
                if (!tiles.contains(TilePos.ofBlock(cx * 8, cz * 8)) || ZoneRules.zonable(cx, cz, cols, tiles) == null) continue;
                if (!buildable(p.level(), city, cols, k)) continue; // a person sees "too steep" and zones elsewhere
                spots.add(new long[] {k, rx * rx + rz * rz});
            }
            spots.sort((a, b) -> Long.compare(a[1], b[1]));
            if (spots.isEmpty()) { grow(p, city, need); continue; }
            Map<ZoneType, List<Long>> paint = new EnumMap<>(ZoneType.class);
            for (int i = 0; i < Math.min(8, spots.size()); i++) {
                long k = spots.get(i)[0];
                int cx = com.cloudkingtv.townstead.road.RoadRaster.keyX(k) - hcx, cz = com.cloudkingtv.townstead.road.RoadRaster.keyZ(k) - hcz;
                double r = Math.hypot(cx, cz);
                ZoneType z = low;
                if (need == ZoneType.Need.HOMES && r <= 10 && Milestone.unlocked(city.milestone(), ZoneType.RESIDENTIAL_HIGH)
                        && m.demand.plannedPeople() >= ZoneType.RESIDENTIAL_HIGH.minPopulation) z = ZoneType.RESIDENTIAL_HIGH;
                if (need == ZoneType.Need.SHOPS && r <= 8 && Milestone.unlocked(city.milestone(), ZoneType.COMMERCIAL_HIGH)
                        && m.demand.plannedPeople() >= ZoneType.COMMERCIAL_HIGH.minPopulation) z = ZoneType.COMMERCIAL_HIGH;
                paint.computeIfAbsent(z, q -> new ArrayList<>()).add(k);
            }
            for (var en : paint.entrySet()) {
                LandEditor.paint(p, city, en.getKey(), en.getValue());
                log("zoned " + en.getValue().size() + " " + en.getKey() + " (demand " + demand + ", free " + free + ")");
            }
        }
    }

    /** The next street into the part of town a need grows in; land when there's none left to lay. */
    static void grow(ServerPlayer p, City city, ZoneType.Need need) {
        int tries = 0;
        boolean any = false;
        for (int[] e : edges(city)) {
            // A refused street is tried again now and then (materials arrive, a neighbour's road joins it).
            int fails = failedEdges.getOrDefault(key(e), 0);
            if (builtEdges.contains(key(e)) || (fails >= 3 && (t / THINK) % 30 != 0)) continue;
            // Industry's streets reach the east side (rx 9 on); everything else stays west of it.
            int maxX = e[0] == 1 ? Math.max(e[2], e[3]) : e[1];
            boolean east = maxX >= 9;
            if ((need == ZoneType.Need.WORKSHOPS) != east) continue;
            // Only edges joined to what's built (a person extends the network).
            if (!touches(e)) continue;
            any = true;
            if (lay(p, city, e) || ++tries >= 3) return;
        }
        if (!any) buyLand(p, city, need);
    }

    static boolean touches(int[] e) {
        int ax = e[0] == 1 ? e[2] : e[1], az = e[0] == 1 ? e[1] : e[2], bx = e[0] == 1 ? e[3] : e[1], bz = e[0] == 1 ? e[1] : e[3];
        for (String k : builtEdges) {
            String[] f = k.split(":");
            int h = Integer.parseInt(f[0]), line = Integer.parseInt(f[1]), a = Integer.parseInt(f[2]), b = Integer.parseInt(f[3]);
            int ex0 = h == 1 ? a : line, ez0 = h == 1 ? line : a, ex1 = h == 1 ? b : line, ez1 = h == 1 ? line : b;
            if ((ax == ex0 && az == ez0) || (ax == ex1 && az == ez1) || (bx == ex0 && bz == ez0) || (bx == ex1 && bz == ez1)) return true;
        }
        return false;
    }

    static long lastLand = -100000, landWanted = -1000000;

    static void buyLand(ServerPlayer p, City city, ZoneType.Need need) {
        long now = p.level().getGameTime();
        if (now - lastLand < 600) return;
        CityManager cities = CityManager.get(p.level().getServer());
        TilePos hallTile = TilePos.of(hall);
        TilePos best = null;
        double bestScore = Double.MAX_VALUE;
        Set<TilePos> owned = city.tiles();
        for (TilePos tp : owned) for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            TilePos c = tp.offset(d[0], d[1]);
            if (TownActions.buyProblem(owned, city.milestone(), city.treasury(), c, q -> cities.blockedForBuying(p.level(), city, q)) != null) continue;
            int dx = c.x() - hallTile.x(), dz = c.z() - hallTile.z();
            // Industry grows east; everything else rings the hall.
            double score = need == ZoneType.Need.WORKSHOPS ? -dx * 10 + Math.abs(dz) * 3 : Math.hypot(dx, dz) + (dx > 1 ? 2 : 0);
            if (score < bestScore) { bestScore = score; best = c; }
        }
        if (best == null) {
            String why = owned.size() >= Milestone.tileAllowance(city.milestone()) ? "allowance" : "money";
            if (why.equals("money")) landWanted = now;
            if (now - lastLand > 2400) { log("can't buy land (" + why + ") for " + need); lastLand = now; }
            return;
        }
        int before = owned.size();
        TownActions.buyTile(p, city, best);
        lastLand = now;
        if (city.tiles().size() > before) log("bought tile " + best.x() + "," + best.z() + " for " + need + " (" + city.tiles().size() + " tiles)");
    }

    /** The most jammed lane becomes a street, once the purse allows (one a round). */
    static void upgradeLanes(ServerPlayer p, City city, TownModel m) {
        if (city.treasury() < 60 * TownEconomy.EMERALD) return;
        RoadNetwork net = RoadNetwork.get(p.level());
        com.cloudkingtv.townstead.road.RoadSegment worst = null;
        double ratio = 1.0;
        for (var seg : net.segments()) {
            if (!seg.city().equals(city.id()) || seg.type() != RoadType.LANE) continue;
            double r = m.traffic.ratio(seg);
            if (r > ratio) { ratio = r; worst = seg; }
        }
        if (worst == null) return;
        var c = worst.curve();
        for (Material mat : List.of(Material.STONE_BRICKS, Material.DIRT)) {
            int have = Stock.count(city.stock(), mat);
            if (have < 256) supply(p, city, mat, 256 - have, "street");
        }
        int stranded = stranded(p, city);
        RoadEditor.bulldoze(p, city, worst.id());
        RoadEditor.build(p, city, RoadType.STREET, RoadCurve.straight(c.ax(), c.az(), c.bx(), c.bz()));
        boolean street = RoadNetwork.get(p.level()).segments().stream().anyMatch(sg -> sg.type() == RoadType.STREET
                && Math.abs(sg.curve().ax() - c.ax()) + Math.abs(sg.curve().az() - c.az()) < 1 || sg.type() == RoadType.STREET
                && Math.abs(sg.curve().bx() - c.ax()) + Math.abs(sg.curve().bz() - c.az()) < 1);
        if (street && stranded(p, city) > stranded) {
            // The wider street cut some lots off their road: undo, as a person would.
            RoadNetwork now = RoadNetwork.get(p.level());
            for (var sg : List.copyOf(now.segments())) {
                if (sg.type() == RoadType.STREET && (Math.abs(sg.curve().ax() - c.ax()) + Math.abs(sg.curve().az() - c.az()) < 1
                        || Math.abs(sg.curve().bx() - c.ax()) + Math.abs(sg.curve().bz() - c.az()) < 1)) RoadEditor.bulldoze(p, city, sg.id());
            }
            street = false;
        }
        if (!street) RoadEditor.build(p, city, RoadType.LANE, RoadCurve.straight(c.ax(), c.az(), c.bx(), c.bz())); // put the lane back
        log("lane " + worst.id() + " at " + Math.round(ratio * 100) + "% " + (street ? "now a street" : "kept (street refused)"));
    }

    /** Buildings in town with no road in reach of their front. */
    static int stranded(ServerPlayer p, City city) {
        var cols = ZoneRules.roadColumns(RoadNetwork.get(p.level()), city.id());
        int n = 0;
        for (Building b : LandData.get(p.level()).buildingsOf(city.id())) if (ZoneRules.frontage(b.cellX, b.cellZ, cols, city.tiles()) == null) n++;
        return n;
    }

    /** Coach lines round the grid once the roads jam: a coach house, then a loop at a growing ring. */
    static void transit(ServerPlayer p, City city, TownModel m, List<Building> town) {
        if (m.traffic.trips() < 150 || m.traffic.flowPercent() >= 75 || city.lines().size() >= 4) return;
        if (!Milestone.unlocked(city.milestone(), ServiceType.COACH_HOUSE)) return;
        if (town.stream().noneMatch(b -> ServiceType.COACH_HOUSE.key().equals(b.service))) { place(p, city, ServiceType.COACH_HOUSE, 4, -5, false); return; }
        if (town.stream().anyMatch(b -> ServiceType.COACH_HOUSE.key().equals(b.service) && b.state != Building.State.ACTIVE)) return;
        int ring = 5 + city.lines().size() * 5;
        RoadNetwork net = RoadNetwork.get(p.level());
        List<TransitPayloads.StopPick> picks = new ArrayList<>();
        for (double[] a : new double[][] {{-ring, -ring}, {0, -ring}, {ring, -ring}, {ring, 0}, {ring, ring}, {0, ring}, {-ring, ring}, {-ring, 0}}) {
            net.nearestSegment(bx((int) a[0]), bz((int) a[1]), 12).filter(h -> h.segment().city().equals(city.id()))
                    .ifPresent(h -> { if (picks.stream().noneMatch(q -> q.segment() == h.segment().id())) picks.add(new TransitPayloads.StopPick(h.segment().id(), h.t())); });
        }
        if (picks.size() < 3) return;
        int before = city.lines().size();
        CoachLines.create(p, city, picks);
        if (city.lines().size() > before) log("coach line " + city.lines().size() + " with " + picks.size() + " stops (flow " + m.traffic.flowPercent() + "%)");
    }

    // ---------------------------------------------------------------- the day's books

    static void record(MinecraftServer server) {
        if (done) return;
        ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
        ServerLevel level = p.level();
        City city = city(server);
        TownModel m = TownEconomy.model(level, city);
        List<Building> town = LandData.get(level).buildingsOf(city.id());
        int people = 0, hh = 0, jobs = 0, active = 0, sites = 0, paused = 0, abandoned = 0, services = 0;
        for (Building b : town) {
            if (b.working()) { people += Residents.people(b); hh += b.households; jobs += b.jobs; }
            switch (b.state) {
                case ACTIVE -> active++;
                case ABANDONED -> abandoned++;
                case PAUSED -> { paused++; sites++; }
                default -> sites++;
            }
            if (b.civic()) services++;
        }
        Finance.Report r = Finance.daily(m);
        var ind = m.industry();
        double made = 0;
        for (Material mat : Material.values()) made += ind.made(mat);
        long starved = ind.flows().stream().filter(f -> f.shortOf() != null).count();
        int stock = city.stock().values().stream().mapToInt(Integer::intValue).sum();
        Budget b = city.budget();
        days.println(String.join(",", List.of(String.valueOf(day), String.format("%.3f", hours()), String.valueOf(people), String.valueOf(hh), String.valueOf(jobs),
                String.valueOf(town.size()), String.valueOf(active), String.valueOf(sites), String.valueOf(paused), String.valueOf(abandoned),
                String.valueOf(city.milestone()), String.valueOf(city.tiles().size()), Budget.format(city.treasury()), Budget.format(r.income()),
                Budget.format(r.expenses()), Budget.format(r.net()), Budget.format(r.exports()), String.valueOf(b.tax(0)), String.valueOf(b.tax(1)),
                String.valueOf(b.tax(2)), String.valueOf(b.tax(3)), String.valueOf(m.townHappiness()), String.valueOf(m.unemploymentRate()),
                String.valueOf(m.demand.residential()), String.valueOf(m.demand.commercial()), String.valueOf(m.demand.industrial()), String.valueOf(m.demand.office()),
                String.valueOf(m.traffic.flowPercent()), String.valueOf(m.traffic.trips()), String.valueOf(m.traffic.riders()), String.valueOf(stock),
                String.valueOf(Math.round(made)), String.valueOf(starved), Budget.format(importedCents), String.valueOf(deposited),
                String.valueOf(RoadNetwork.get(level).segments().size()), String.valueOf(services),
                String.format("%.1f", server.getAverageTickTimeNanos() / 1e6))));
        days.flush();
        day++;
        // Why buildings are in trouble (or were abandoned), by reason.
        Map<String, Integer> why = new HashMap<>();
        for (Building x : town) {
            if (x.trouble <= 0 && x.state != Building.State.ABANDONED) continue;
            var c = com.cloudkingtv.townstead.building.Progression.trouble(x, m);
            String k = (x.state == Building.State.ABANDONED ? "abandoned:" : "trouble:") + x.zone.name() + ":" + (c == null ? "-" : c.getString());
            why.merge(k, 1, Integer::sum);
        }
        if (!why.isEmpty()) log("trouble " + why);
        var cols = ZoneRules.roadColumns(RoadNetwork.get(level), city.id());
        int shown = 0;
        for (Building x : town) {
            if (shown >= 3 || ZoneRules.frontage(x.cellX, x.cellZ, cols, city.tiles()) != null) continue;
            int near = 99;
            for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
                if (cols.containsKey(com.cloudkingtv.townstead.road.RoadRaster.key((int) x.centerX() + dx, (int) x.centerZ() + dz))) near = Math.min(near, Math.max(Math.abs(dx), Math.abs(dz)));
            }
            log("stranded #" + x.id + " " + x.zone + " " + x.state + " at " + (x.cellX - hcx) + "," + (x.cellZ - hcz) + " front " + x.front + " " + x.wide + "x" + x.deep
                    + " nearest road " + near + " blocks; tile owned " + city.tiles().contains(TilePos.ofBlock(x.cellX * 8, x.cellZ * 8)));
            shown++;
        }
        // The books: taxes up while losing money with little in hand, a loan in a pinch, taxes back down when flush.
        long net = r.net();
        long upkeep = 0;
        for (long u : r.upkeep()) upkeep += u;
        long taxes = 0;
        for (long x : r.taxes()) taxes += x;
        log("books: taxes " + Budget.format(taxes) + " fares " + Budget.format(r.fares()) + " exports " + Budget.format(r.exports()) + " | upkeep "
                + Budget.format(upkeep) + " roads " + Budget.format(r.roads()) + " loans " + Budget.format(r.loans()) + " policies " + Budget.format(r.policies()));
        if (net < 0 && r.income() > 0 && city.treasury() < 40 * TownEconomy.EMERALD) {
            for (int k = 0; k < 3; k++) if (b.tax(k) < 12) TownEconomy.setTax(p, city, k, b.tax(k) + 1);
            log("taxes up to " + b.tax(0) + "/" + b.tax(1) + "/" + b.tax(2) + " (net " + Budget.format(net) + ")");
        } else if (net > 0 && city.treasury() > 150 * TownEconomy.EMERALD) {
            for (int k = 0; k < 3; k++) if (b.tax(k) > 9) TownEconomy.setTax(p, city, k, b.tax(k) - 1);
        }
        boolean trimmed = b.budget(ServiceType.Department.PARKS) < 100;
        if (city.treasury() < 0 && net < 0 && !trimmed) {
            for (var d : List.of(ServiceType.Department.PARKS, ServiceType.Department.HEALTH, ServiceType.Department.EDUCATION, ServiceType.Department.POLICE))
                TownEconomy.setBudget(p, city, d.ordinal(), 70);
            log("service budgets cut to 70%");
        } else if (trimmed && city.treasury() > 100 * TownEconomy.EMERALD && net > 0) {
            for (var d : List.of(ServiceType.Department.PARKS, ServiceType.Department.HEALTH, ServiceType.Department.EDUCATION, ServiceType.Department.POLICE))
                TownEconomy.setBudget(p, city, d.ordinal(), 100);
            log("service budgets back to 100%");
        }
        if (city.treasury() < 0 && b.loans.isEmpty()) {
            Loan.Offer o = city.milestone() < Milestone.TINY_TOWN.ordinal() ? Loan.Offer.SMALL
                    : city.milestone() < Milestone.BUSY_TOWN.ordinal() ? Loan.Offer.MEDIUM : Loan.Offer.LARGE;
            TownEconomy.takeLoan(p, city, o.ordinal());
            log("took a " + o + " loan (treasury " + Budget.format(city.treasury()) + ")");
        }
        // Stuck: no more people for five days.
        if (people <= lastPop) stuckDays++;
        else stuckDays = 0;
        lastPop = people;
        if (stuckDays == 5) {
            StringBuilder sb = new StringBuilder("STUCK at " + people + ": demand R" + m.demand.residential() + " C" + m.demand.commercial() + " I"
                    + m.demand.industrial() + " O" + m.demand.office() + "; paused " + paused + " needs ");
            for (Building x : town) if (x.state == Building.State.PAUSED) sb.append(x.needs).append(' ');
            log(sb.toString());
            log("free lots: " + lotReport(level, city));
        }
    }

    static java.lang.reflect.Method siteHeight;

    /** Whether a single-cell lot here passes the game's own site check (frontage, slope, water, its road's level). */
    static boolean buildable(ServerLevel level, City city, Map<Long, com.cloudkingtv.townstead.road.RoadRaster.Cell> cols, long key) {
        int cx = com.cloudkingtv.townstead.road.RoadRaster.keyX(key), cz = com.cloudkingtv.townstead.road.RoadRaster.keyZ(key);
        Direction front = ZoneRules.frontage(cx, cz, cols, city.tiles());
        if (front == null) return false;
        try {
            if (siteHeight == null) {
                siteHeight = TownGrowth.class.getDeclaredMethod("siteHeight", ServerLevel.class, List.class, Map.class);
                siteHeight.setAccessible(true);
            }
            return siteHeight.invoke(null, level, Building.lotCells(cx, cz, front, 1, 1), cols) != null;
        } catch (ReflectiveOperationException ex) { return true; }
    }

    /** Why each free zoned lot isn't growing: no frontage, or the site check (slope, water, too far off its road). */
    static String lotReport(ServerLevel level, City city) {
        LandData land = LandData.get(level);
        var cols = ZoneRules.roadColumns(RoadNetwork.get(level), city.id());
        Map<String, Integer> out = new java.util.TreeMap<>();
        try {
            var site = TownGrowth.class.getDeclaredMethod("siteHeight", ServerLevel.class, List.class, Map.class);
            site.setAccessible(true);
            for (var e : land.zones().entrySet()) {
                if (!e.getValue().city().equals(city.id()) || land.buildingAt(e.getKey()) != null) continue;
                int cx = com.cloudkingtv.townstead.road.RoadRaster.keyX(e.getKey()), cz = com.cloudkingtv.townstead.road.RoadRaster.keyZ(e.getKey());
                Direction front = ZoneRules.frontage(cx, cz, cols, city.tiles());
                String why;
                if (front == null) why = "no-frontage";
                else why = site.invoke(null, level, Building.lotCells(cx, cz, front, 1, 1), cols) == null ? "bad-site" : "ok";
                out.merge(e.getValue().type() + ":" + why, 1, Integer::sum);
            }
        } catch (ReflectiveOperationException ex) { return ex.toString(); }
        return out.toString();
    }

    static void finish(MinecraftServer server, City city) {
        if (done) return;
        done = true;
        log("END milestone " + Milestone.of(city.milestone()).key() + " treasury " + Budget.format(city.treasury()) + " imported " + Budget.format(importedCents)
                + " deposited " + deposited);
        days.close();
        events.close();
        Runtime.getRuntime().halt(0);
    }

    private AutoPlayer() {}
}
