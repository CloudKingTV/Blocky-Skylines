package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.LandEditor;
import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.city.Stock;
import com.cloudkingtv.townstead.city.TilePos;
import com.cloudkingtv.townstead.client.planner.AdvisorProbe;
import com.cloudkingtv.townstead.client.planner.PlannerClient;
import com.cloudkingtv.townstead.economy.Budget;
import com.cloudkingtv.townstead.economy.Finance;
import com.cloudkingtv.townstead.economy.TownEconomy;
import com.cloudkingtv.townstead.progress.Milestone;
import com.cloudkingtv.townstead.registry.TownsteadRegistry;
import com.cloudkingtv.townstead.road.Material;
import com.cloudkingtv.townstead.road.RoadCurve;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadRaster;
import com.cloudkingtv.townstead.road.RoadType;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.sim.TownModel;
import com.cloudkingtv.townstead.zone.LandData;
import com.cloudkingtv.townstead.zone.ZoneRules;
import com.cloudkingtv.townstead.zone.ZoneType;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Scratch-only test for Phase 11's "done" (run/newcomer.flag): a newcomer who has never read the README founds a
 * town in survival and then does only what the planner's advisor says. Each round it reads the advisor's first card
 * (the first steps, while they're up) and does what that card's text tells a player to do, in the simplest way a
 * person would; on the last step ("grow") it also works through the tips behind "Next". It gathers by hand at the
 * same pace as the Phase 9 autoplayer (about 1,500 blocks per speed-1 hour) and otherwise buys in with emeralds as
 * the cards suggest. It succeeds when the town reaches Worthy Village; it fails if a card leaves it with nothing to
 * do for long, or after -Dtownstead.newcomer.hours speed-1 hours. Uses the autoplayer's site search and street grid.
 */
@EventBusSubscriber(modid = "townstead", value = Dist.CLIENT)
public final class NewcomerBot {
    static final boolean ON = new File("newcomer.flag").exists();
    static final double MAX_HOURS = Double.parseDouble(System.getProperty("townstead.newcomer.hours", "6"));
    static final int THINK = 100;
    /** -Dtownstead.newcomer.avoid=x,z: pick a site at least 500 blocks from there (a second run somewhere else). */
    static final int[] AVOID = System.getProperty("townstead.newcomer.avoid") == null ? null
            : java.util.Arrays.stream(System.getProperty("townstead.newcomer.avoid").split(",")).mapToInt(Integer::parseInt).toArray();
    static final int GATHER_PER_HOUR = 1500;

    static int t;
    static volatile boolean founded, done;
    static long startTick, gathered, lastTaxes = -100000, lastService = -100000, lastUtility = -100000;
    static PrintWriter events;
    static String lastCard = "", shotCard = "";
    static int shots;
    static int idleRounds;
    static final List<int[]> laid = new ArrayList<>();

    static void log(String s) {
        String line = String.format("[New] h=%.2f %s", hours(), s);
        System.out.println(line);
        if (events != null) { events.println(line); events.flush(); }
    }

    static double hours() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        return server == null || !founded ? 0 : (server.getTickCount() - startTick) * AutoPlayer.SPEED / 72000.0;
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post e) {
        if (!ON || done) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        t++;
        MinecraftServer server = mc.getSingleplayerServer();
        if (t == 30) {
            // A first-time player: steps and tips on, nothing dismissed.
            TownsteadConfig.SHOW_ADVISOR.set(true);
            TownsteadConfig.FIRST_STEPS.set(true);
            TownsteadConfig.DISMISSED_TIPS.set(List.of());
        }
        if (t == 40) server.execute(() -> { AutoPlayer.find(server); flatSite(server); });
        if (t == 240) server.execute(() -> found(server));
        if (!founded || t % THINK != 0) return;
        CitySummary c = PlannerClient.city().orElse(null);
        if (c == null) {
            // Open the planner (the hint says to press M); again if it didn't take.
            if (mc.gui.screen() == null) PlannerClient.requestOpen();
            System.out.println("[New] opening the planner; screen " + mc.gui.screen());
            return;
        }
        // What the advisor shows the player now (worked out on the client, as the planner draws it).
        List<Object[]> tips = AdvisorProbe.tipArgs(c);
        if (!tips.isEmpty() && !card(tips.getFirst()).equals(shotCard)) {
            shotCard = card(tips.getFirst());
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "newcomer_" + (++shots) + "_" + shotCard.replace(':', '_') + ".png", mc.gameRenderer.mainRenderTarget(), 1, msg -> {});
        }
        server.execute(() -> { try { think(server, tips); } catch (Throwable ex) { log("think failed: " + ex); ex.printStackTrace(System.out); } });
    }

    /**
     * Where the hint says to put the hall: open, fairly flat land. The flattest dry spot for the town's first 3x3 tiles
     * (192 blocks across) within a walk of spawn, judged from the world generator.
     */
    static void flatSite(MinecraftServer server) {
        ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
        var level = p.level();
        var gen = level.getChunkSource().getGenerator();
        var rs = level.getChunkSource().randomState();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x0 = -1200; x0 <= 1200; x0 += 64) for (int z0 = -1200; z0 <= 1200; z0 += 64) {
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, wet = 0;
            for (int dx = -96; dx <= 96; dx += 12) for (int dz = -96; dz <= 96; dz += 12) {
                int h = gen.getBaseHeight(x0 + dx, z0 + dz, Heightmap.Types.WORLD_SURFACE_WG, level, rs);
                if (h - gen.getBaseHeight(x0 + dx, z0 + dz, Heightmap.Types.OCEAN_FLOOR_WG, level, rs) > 0) wet++;
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
            if (wet > 6) continue;
            if (AVOID != null && Math.hypot(x0 - AVOID[0], z0 - AVOID[1]) < 500) continue;
            double score = (max - min) * 20 + Math.hypot(x0, z0) * 0.01;
            if (score < bestScore) { bestScore = score; best = new BlockPos(x0, 0, z0); }
        }
        AutoPlayer.centre = best;
        p.teleportTo(level, best.getX() + 0.5, 150, best.getZ() + 0.5, Set.of(), 0f, 60f, true);
        System.out.println("[New] flattest site " + best + " score " + bestScore);
    }

    /** Places the Town Hall the welcome gives (where the hint says: open, fairly flat land), in survival. */
    static void found(MinecraftServer server) {
        ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
        var level = p.level();
        BlockPos c = AutoPlayer.centre;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
        BlockPos hall = new BlockPos(c.getX(), y, c.getZ());
        BlockState state = TownsteadRegistry.TOWN_HALL.get().defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH);
        level.setBlock(hall, state, 3);
        state.getBlock().setPlacedBy(level, hall, state, p, TownsteadRegistry.TOWN_HALL_ITEM.get().getDefaultInstance());
        AutoPlayer.hall = hall;
        AutoPlayer.hcx = Math.floorDiv(hall.getX(), 8);
        AutoPlayer.hcz = Math.floorDiv(hall.getZ(), 8);
        new File("newcomer").mkdirs();
        try { events = new PrintWriter(new FileWriter("newcomer/events.log")); } catch (java.io.IOException ex) { throw new RuntimeException(ex); }
        startTick = server.getTickCount();
        founded = true;
        // What the first buildings take, for the first steps' wording.
        for (Object[] pr : new Object[][] {{ZoneType.RESIDENTIAL, ""}, {ZoneType.COMMERCIAL, ""}, {ZoneType.INDUSTRIAL, ""}, {ZoneType.CIVIC, "windmill"}, {ZoneType.CIVIC, "well"}}) {
            Building b = new Building(0, AutoPlayer.city(server).id(), (ZoneType) pr[0], AutoPlayer.hcx + 20, AutoPlayer.hcz + 20, Direction.SOUTH, hall.getY(), 12345L, 0, 0);
            b.service = (String) pr[1]; b.level = 1;
            if (b.civic()) { b.wide = b.serviceType().wide; b.deep = b.serviceType().deep; }
            var plan = com.cloudkingtv.townstead.building.BuildingGenerator.plan(b);
            System.out.println("[New] cost " + plan.kind() + " " + com.cloudkingtv.townstead.building.TownGrowth.bill(plan));
        }
        log("founded " + AutoPlayer.city(server).name() + " at " + hall + ", guide in inventory: " + (p.getInventory().countItem(TownsteadRegistry.GUIDE.get()) > 0));
    }

    // ---------------------------------------------------------------- reading the advisor

    static void think(MinecraftServer server, List<Object[]> tips) {
        if (done) return;
        ServerPlayer p = server.getPlayerList().getPlayers().getFirst();
        City city = AutoPlayer.city(server);
        TownModel m = TownEconomy.model(p.level(), city);
        int people = m.demand.population();
        if (city.milestone() >= 1) {
            log("SUCCESS: " + Milestone.of(city.milestone()).key() + " with " + people + " people");
            finish();
            return;
        }
        if (hours() >= MAX_HOURS) { log("FAIL: out of time at " + people + " people, cards " + cards(tips)); finish(); return; }
        if (tips.isEmpty()) return;
        String card = card(tips.getFirst());
        if (!card.equals(lastCard)) { log("card " + card + " (" + people + " people; all: " + cards(tips) + ")"); lastCard = card; }
        boolean acted = act(p, city, m, tips.getFirst());
        // On the last first step the card says to grow; a player then reads the tips behind "Next".
        if (card.equals("steps:grow")) for (Object[] tip : tips.subList(1, tips.size())) acted |= act(p, city, m, tip);
        idleRounds = acted ? 0 : idleRounds + 1;
        if (idleRounds > 0 && idleRounds % 60 == 0) log("idle for " + idleRounds + " rounds on " + cards(tips) + " (" + people + " people, "
                + LandData.get(p.level()).buildingsOf(city.id()).size() + " buildings, treasury " + Budget.format(city.treasury()) + ")");
    }

    static String card(Object[] tip) {
        String key = (String) tip[0];
        return key.equals("steps") ? "steps:" + ((Object[]) tip[1])[2] : key;
    }

    static String cards(List<Object[]> tips) {
        return String.join(", ", tips.stream().map(NewcomerBot::card).toList());
    }

    /** Does what one card says; true if it did something. */
    static boolean act(ServerPlayer p, City city, TownModel m, Object[] tip) {
        Object[] args = (Object[]) tip[1];
        return switch (card(tip)) {
            case "steps:stock" -> stock(p, city);
            case "steps:road" -> road(p, city, RoadType.LANE);
            case "steps:homes" -> zone(p, city, ZoneType.RESIDENTIAL, 8);
            case "steps:utilities" -> utilities(p, city, m);
            case "steps:jobs" -> zone(p, city, ZoneType.COMMERCIAL, 3) | zone(p, city, ZoneType.INDUSTRIAL, 3);
            case "steps:grow" -> keepStocked(p, city) | bars(p, city, m);
            case "waiting", "supply", "supply_buy" -> keepStocked(p, city);
            case "money" -> taxes(p, city);
            case "more_utilities" -> moreUtilities(p, city, m, (Integer) args[0], (Integer) args[1]);
            case "service" -> service(p, city, m, (Component) args[0]);
            case "demand" -> bars(p, city, m);
            default -> false; // milestone, good_town, unhappy, traffic, starved: nothing a newcomer acts on directly
        };
    }

    // ---------------------------------------------------------------- what each card says

    /** "Craft a Town Storehouse, place it on your land and use it with dirt, cobblestone, logs and planks in hand." */
    static boolean stock(ServerPlayer p, City city) {
        if (city.storehouses().isEmpty()) {
            BlockPos at = AutoPlayer.hall.east(2);
            BlockState sh = TownsteadRegistry.TOWN_STOREHOUSE.get().defaultBlockState();
            p.level().setBlock(at, sh, 3);
            sh.getBlock().setPlacedBy(p.level(), at, sh, p, TownsteadRegistry.TOWN_STOREHOUSE_ITEM.get().getDefaultInstance());
            log("placed a storehouse at " + at);
        }
        boolean any = false;
        for (Material mat : List.of(Material.DIRT, Material.COBBLESTONE, Material.LOGS, Material.PLANKS)) any |= gather(p, city, mat, 64, "stock");
        return any;
    }

    /** Blocks gathered by hand so far may not run ahead of the hourly pace (a few hundred to start with). */
    static boolean gather(ServerPlayer p, City city, Material mat, int n, String why) {
        long allowance = 512 + Math.round(hours() * GATHER_PER_HOUR) - gathered;
        if (allowance < n) return false;
        CityManager.get(p.level().getServer()).editStock(p.level().getServer(), city, st -> Stock.add(st, mat.item(), n));
        gathered += n;
        log("gathered " + n + " " + mat + " by hand for " + why + " (" + gathered + " so far)");
        return true;
    }

    /** "Pick the Roads tool, press 1 for a lane, then click where the road starts and where it ends." Then the next one on. */
    static boolean road(ServerPlayer p, City city, RoadType type) {
        for (int[] e : AutoPlayer.edges(city)) {
            if (laid.stream().anyMatch(l -> AutoPlayer.key(l).equals(AutoPlayer.key(e)))) continue;
            if (!laid.isEmpty() && !touches(e)) continue;
            double ax = e[0] == 1 ? AutoPlayer.bx(e[2]) : AutoPlayer.bx(e[1]), az = e[0] == 1 ? AutoPlayer.bz(e[1]) : AutoPlayer.bz(e[2]);
            double bx = e[0] == 1 ? AutoPlayer.bx(e[3]) : AutoPlayer.bx(e[1]), bz = e[0] == 1 ? AutoPlayer.bz(e[1]) : AutoPlayer.bz(e[3]);
            for (int k = 0; k <= 8; k++) p.level().getChunk(((int) Math.floor(ax + (bx - ax) * k / 8)) >> 4, ((int) Math.floor(az + (bz - az) * k / 8)) >> 4);
            int before = RoadNetwork.get(p.level()).segments().size();
            RoadEditor.build(p, city, type, RoadCurve.straight(ax, az, bx, bz));
            if (RoadNetwork.get(p.level()).segments().size() != before) {
                laid.add(e);
                log("laid a " + type + " " + AutoPlayer.key(e));
                return true;
            }
            // Refused: most likely short of dirt (the flash says so); gather some and try again next round.
            log("road " + AutoPlayer.key(e) + " refused; dirt " + Stock.count(city.stock(), Material.DIRT));
            return gather(p, city, Material.DIRT, 128, "road") || buy(p, city, Material.DIRT, "road");
        }
        return false;
    }

    static boolean touches(int[] e) {
        int ax = e[0] == 1 ? e[2] : e[1], az = e[0] == 1 ? e[1] : e[2], bx = e[0] == 1 ? e[3] : e[1], bz = e[0] == 1 ? e[1] : e[3];
        for (int[] l : laid) {
            int lx0 = l[0] == 1 ? l[2] : l[1], lz0 = l[0] == 1 ? l[1] : l[2], lx1 = l[0] == 1 ? l[3] : l[1], lz1 = l[0] == 1 ? l[1] : l[3];
            if (ax == lx0 && az == lz0 || ax == lx1 && az == lz1 || bx == lx0 && bz == lz0 || bx == lx1 && bz == lz1) return true;
        }
        return false;
    }

    /**
     * "Drag over the lots beside your road": up to {@code n} free lots that can be zoned and built on, homes and shops
     * nearest the hall, industry ("a little away from homes") farthest from it. Lays the next road when there are none.
     */
    static boolean zone(ServerPlayer p, City city, ZoneType type, int n) {
        LandData land = LandData.get(p.level());
        var cols = ZoneRules.roadColumns(RoadNetwork.get(p.level()), city.id());
        Set<TilePos> tiles = city.tiles();
        List<long[]> spots = new ArrayList<>();
        int steep = 0;
        for (int rx = -40; rx <= 40; rx++) for (int rz = -40; rz <= 40; rz++) {
            if (Math.abs(rx) <= 1 && Math.abs(rz) <= 1) continue;
            if (AutoPlayer.hLine(rz) || AutoPlayer.vLine(rx)) continue; // where the next roads go
            int cx = AutoPlayer.hcx + rx, cz = AutoPlayer.hcz + rz;
            long k = ZoneRules.cellKey(cx, cz);
            if (land.zone(k) != null || land.buildingAt(k) != null) continue;
            if (!tiles.contains(TilePos.ofBlock(cx * 8, cz * 8)) || ZoneRules.zonable(cx, cz, cols, tiles) == null) continue;
            if (!AutoPlayer.buildable(p.level(), city, cols, k)) { steep++; continue; } // the planner says "too steep" and they move on
            long d = (long) rx * rx + (long) rz * rz;
            spots.add(new long[] {k, type == ZoneType.INDUSTRIAL ? -d : d});
        }
        if (spots.isEmpty()) {
            log("no free lots for " + type + " (" + steep + " too steep); laying the next road");
            return road(p, city, RoadType.LANE);
        }
        spots.sort((a, b) -> Long.compare(a[1], b[1]));
        List<Long> cells = new ArrayList<>();
        for (int i = 0; i < Math.min(n, spots.size()); i++) cells.add(spots.get(i)[0]);
        LandEditor.paint(p, city, type, cells);
        log("zoned " + cells.size() + " " + type + " (" + spots.size() + " free, " + steep + " too steep)");
        return true;
    }

    /** "Utilities tool: 1 places a windmill, 3 a well. Put them beside a road." */
    static boolean utilities(ServerPlayer p, City city, TownModel m) {
        List<Building> town = LandData.get(p.level()).buildingsOf(city.id());
        boolean power = town.stream().anyMatch(b -> b.civic() && b.serviceType().kind == ServiceType.Kind.POWER);
        boolean water = town.stream().anyMatch(b -> b.civic() && b.serviceType().kind == ServiceType.Kind.WATER);
        boolean any = false;
        if (!power) any |= placeNearRoad(p, city, ServiceType.WINDMILL);
        if (!water) any |= placeNearRoad(p, city, ServiceType.WELL);
        return any;
    }

    /** The first lot beside a road (nearest the hall) the planner accepts; if none, the reasons it gave, and the next road. */
    static boolean placeNearRoad(ServerPlayer p, City city, ServiceType s) {
        var cols = ZoneRules.roadColumns(RoadNetwork.get(p.level()), city.id());
        java.util.Map<String, Integer> why = new java.util.TreeMap<>();
        List<int[]> cells = new ArrayList<>();
        for (int rx = -40; rx <= 40; rx++) for (int rz = -40; rz <= 40; rz++) cells.add(new int[] {rx, rz});
        cells.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        for (int[] c : cells) {
            int cx = AutoPlayer.hcx + c[0], cz = AutoPlayer.hcz + c[1];
            if (Math.abs(c[0]) <= 1 && Math.abs(c[1]) <= 1) continue;
            if (!city.tiles().contains(TilePos.ofBlock(cx * 8, cz * 8)) || ZoneRules.frontage(cx, cz, cols, city.tiles()) == null) continue;
            Object res = com.cloudkingtv.townstead.building.TownGrowth.placeCivic(p.level(), city, s, cx, cz);
            if (res instanceof Building) { log("placed a " + s.key() + " at " + c[0] + "," + c[1]); return true; }
            why.merge(String.valueOf(res), 1, Integer::sum);
        }
        log("no lot for a " + s.key() + " beside the roads: " + why + "; laying the next road");
        return road(p, city, RoadType.LANE);
    }

    /**
     * "Zone what the bars at the top ask for": the need whose bar is highest, if it asks for more (a quarter full),
     * while fewer than four lots of it stand empty.
     */
    static boolean bars(ServerPlayer p, City city, TownModel m) {
        ZoneType.Need best = null;
        int high = 25;
        for (ZoneType.Need need : List.of(ZoneType.Need.HOMES, ZoneType.Need.SHOPS, ZoneType.Need.WORKSHOPS)) {
            if (m.demand.of(need) > high) { high = m.demand.of(need); best = need; }
        }
        if (best == null) return false;
        LandData land = LandData.get(p.level());
        int free = 0;
        for (var e : land.zones().entrySet()) if (e.getValue().city().equals(city.id()) && e.getValue().type().need == best && land.buildingAt(e.getKey()) == null) free++;
        if (free >= 4) return false;
        ZoneType type = switch (best) { case SHOPS -> ZoneType.COMMERCIAL; case WORKSHOPS -> ZoneType.INDUSTRIAL; default -> ZoneType.RESIDENTIAL; };
        log("the " + best + " bar is at " + high);
        return zone(p, city, type, 4);
    }

    /** "Keep the storehouse stocked: a site that runs out of something stops, with an icon over it." */
    static boolean keepStocked(ServerPlayer p, City city) {
        boolean any = false;
        java.util.Set<Material> wanted = java.util.EnumSet.noneOf(Material.class);
        for (Building b : LandData.get(p.level()).buildingsOf(city.id())) if (b.state == Building.State.PAUSED && b.needs != null) wanted.add(b.needs);
        for (Material mat : wanted) any |= gather(p, city, mat, 64, "a waiting site") || buy(p, city, mat, "a waiting site");
        return any;
    }

    /** "Buy a stack in from the Production page (L)", keeping a few emeralds back. */
    static boolean buy(ServerPlayer p, City city, Material mat, String why) {
        long cost = 64L * mat.importPrice();
        if (city.treasury() - cost < 5 * TownEconomy.EMERALD) return false;
        long before = city.treasury();
        TownEconomy.buy(p, city, mat.ordinal(), 64);
        if (city.treasury() >= before) return false;
        log("bought 64 " + mat + " for " + why + " (" + Budget.format(before - city.treasury()) + ", treasury " + Budget.format(city.treasury()) + ")");
        return true;
    }

    /** "Press L for the budget: raise a tax by a point or two." Once a day at most. */
    static boolean taxes(ServerPlayer p, City city) {
        long now = p.level().getGameTime();
        if (now - lastTaxes < AutoPlayer.DAY) return false;
        lastTaxes = now;
        var b = city.budget();
        for (int k = 0; k < 3; k++) if (b.tax(k) < 13) TownEconomy.setTax(p, city, k, b.tax(k) + 1);
        log("raised taxes to " + b.tax(0) + "/" + b.tax(1) + "/" + b.tax(2) + " (net " + Budget.format(Finance.daily(TownEconomy.model(p.level(), city)).net()) + ")");
        return true;
    }

    /** "Build more" mills or wells where buildings go without. One a day. */
    static boolean moreUtilities(ServerPlayer p, City city, TownModel m, int noPower, int noWater) {
        long now = p.level().getGameTime();
        if (now - lastUtility < AutoPlayer.DAY) return false;
        List<Building> town = LandData.get(p.level()).buildingsOf(city.id());
        boolean power = noPower >= noWater;
        int[] at = AutoPlayer.without(m, town, power, 0, 3);
        ServiceType s = power ? ServiceType.WINDMILL : ServiceType.WELL;
        Building b = AutoPlayer.place(p, city, s, at[0], at[1], false);
        if (b == null) return false;
        lastUtility = now;
        log("placed another " + s.key() + " (" + noPower + " without power, " + noWater + " without water)");
        return true;
    }

    /** "Place one in the middle of them": the named service among the homes it doesn't reach. One a day. */
    static boolean service(ServerPlayer p, City city, TownModel m, Component name) {
        long now = p.level().getGameTime();
        if (now - lastService < AutoPlayer.DAY) return false;
        ServiceType s = null;
        for (ServiceType st : ServiceType.values()) if (st.displayName().getString().equals(name.getString())) s = st;
        if (s == null) return false;
        double sx = 0, sz = 0;
        int n = 0;
        for (Building b : LandData.get(p.level()).buildingsOf(city.id())) {
            if (!b.zone.residential() || !b.working() || m.covered(b, s)) continue;
            sx += b.centerX();
            sz += b.centerZ();
            n++;
        }
        if (n == 0) return false;
        int rx = Math.floorDiv((int) (sx / n), 8) - AutoPlayer.hcx, rz = Math.floorDiv((int) (sz / n), 8) - AutoPlayer.hcz;
        Building b = AutoPlayer.place(p, city, s, rx, rz, false);
        if (b == null) return false;
        lastService = now;
        log("placed a " + s.key() + " among " + n + " homes without one");
        return true;
    }

    static void finish() {
        done = true;
        log("done; gathered " + gathered + " blocks by hand");
        if (events != null) events.close();
    }

    private NewcomerBot() {}
}
