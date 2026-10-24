package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.SyncProbe;
import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.city.TilePos;
import com.cloudkingtv.townstead.client.planner.PlannerClient;
import com.cloudkingtv.townstead.district.Civics;
import com.cloudkingtv.townstead.district.Policy;
import com.cloudkingtv.townstead.network.BuildRoadPayload;
import com.cloudkingtv.townstead.network.DistrictPayloads;
import com.cloudkingtv.townstead.network.EconomyPayloads;
import com.cloudkingtv.townstead.network.HistoryPayload;
import com.cloudkingtv.townstead.network.LandEditPayloads;
import com.cloudkingtv.townstead.network.PlannerTogglePayload;
import com.cloudkingtv.townstead.network.RenameTownPayload;
import com.cloudkingtv.townstead.network.StorehousePayloads;
import com.cloudkingtv.townstead.road.Material;
import com.cloudkingtv.townstead.road.RoadCurve;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadNode;
import com.cloudkingtv.townstead.road.RoadRaster;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.road.RoadType;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.zone.LandData;
import com.cloudkingtv.townstead.zone.ZoneRules;
import com.cloudkingtv.townstead.zone.ZoneType;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * Scratch-only test bot for Phase 10: a scripted player on a dedicated server (-Dtownstead.bot=alice|bob), acting
 * only through what a real client sends: chat commands, block placement and the planner's payloads.
 * Alice takes over the big town from its long-gone mayor with /townstead transfer, makes Bob her co-mayor and
 * runs the town. Bob founds a town of his own far away, first tries what a griefer would (edits to Alice's town
 * before he's co-mayor, a payload flood, NaN roads, far-off block screens, a rename), then runs his town and,
 * once he's co-mayor, takes turns editing Alice's. Both log "[Sync] C" lines (see {@link SyncProbe}) to compare
 * with the server's, and "[Bot]" lines for what they did and the checks they made.
 */
@EventBusSubscriber(modid = "townstead", value = Dist.CLIENT)
public final class MultiplayerBot {
    static final String ROLE = System.getProperty("townstead.bot", "");
    static final String SHARED_TOWN = "Dev's Town";
    /** When Alice lights her fire, in ticks after joining. */
    static final int IGNITE = Integer.getInteger("townstead.bot.ignite", 12000);
    static int t;
    static final Random RNG = new Random(ROLE.hashCode());
    static PrintWriter log;
    /** Bob's town site and state of founding. */
    static BlockPos site;
    static int foundTries;
    static boolean founded, laidOut;
    static int foundAt = -1;
    /** Checks waiting on a later tick: tick, then the check. */
    static final List<Object[]> CHECKS = new ArrayList<>();
    static boolean undoNext, GRIEFING;

    /** True the first time a step runs in this game directory (it leaves a marker), false after (and after restarts). */
    static boolean once(String step) {
        java.io.File f = new java.io.File(step + ".done");
        if (step.endsWith(".sent")) f = new java.io.File(step);
        if (f.exists()) return false;
        try { f.createNewFile(); } catch (java.io.IOException ignored) {}
        return true;
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        if (ROLE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) { t = 0; return; }
        t++;
        if (t == 1) log("joined as " + mc.player.getName().getString());
        if (t % 20 == 0) probe(mc);
        try {
            for (Object[] c : List.copyOf(CHECKS)) {
                if ((int) c[0] <= t) { CHECKS.remove(c); ((Runnable) c[1]).run(); }
            }
            if (ROLE.equals("alice")) alice(mc);
            else bob(mc);
        } catch (RuntimeException e) {
            log("ERROR " + e);
            e.printStackTrace();
        }
    }

    // ---------------------------------------------------------------- Alice

    static void alice(Minecraft mc) {
        if (t == 100) cmd(mc, "townstead transfer Alice " + SHARED_TOWN);
        if (t == 200 || t == IGNITE + 160 || t % 2400 == 1200) mine(mc).ifPresent(c -> {
            List<String> burning = new ArrayList<>();
            for (Building b : ClientLand.get().buildingsOf(c.id())) if (b.fire > 0) burning.add(b.id + "(" + b.fire + ")");
            log("burning now in " + c.name() + ": " + burning);
        });
        if (t == 140) mine(mc).ifPresentOrElse(c -> log("CHECK transfer: Alice now runs " + c.name() + " PASS"), () -> log("CHECK transfer FAIL"));
        if (t >= 4800 && t % 2400 == 0) cmd(mc, "townstead comayor add Bob");
        // Ten minutes in: a fire in the town, for the brigade (and the restart test). Out of the planner first, which
        // puts her body back where it was, then over to the town.
        if (t == IGNITE - 100 && !new java.io.File("ignite.done").exists()) plan(false);
        if (t == IGNITE && once("ignite")) {
            mine(mc).ifPresent(c -> cmd(mc, "tp @s " + c.townHall().getX() + " " + (c.townHall().getY() + 2) + " " + (c.townHall().getZ() + 12)));
        }
        if (t == IGNITE + 60 && new java.io.File("ignite.done").exists() && !new java.io.File("ignite.sent").exists()) {
            once("ignite.sent");
            cmd(mc, "townstead ignite");
        }
        if (t >= 400 && t % 400 == 0 && !(t > IGNITE - 200 && t < IGNITE + 200)) {
            if (t % 6000 == 0) { plan(false); return; } // now and then she steps out of the planner
            mine(mc).ifPresent(c -> act(mc, c));
        }
    }

    // ---------------------------------------------------------------- Bob

    static void bob(Minecraft mc) {
        Optional<CitySummary> shared = byName(SHARED_TOWN);
        if (t == 100) cmd(mc, "gamemode creative");
        if (!founded && t >= 140 && t % 100 == 40 && foundTries < 16) {
            if (mine(mc).isPresent()) { founded = true; foundAt = t; log("already have a town"); return; }
            if (shared.isEmpty()) return;
            BlockPos hall = shared.get().townHall();
            int[][] away = {{1600, 200}, {-1600, 0}, {0, 1600}, {0, -1600}, {1200, 1200}, {-1200, -1200}, {1200, -1200}, {-1200, 1200}};
            int[] a = away[(foundTries / 2) % away.length];
            BlockPos candidate = new BlockPos(hall.getX() + a[0], 0, hall.getZ() + a[1]);
            if (foundTries % 2 == 0) {
                cmd(mc, "tp @s " + candidate.getX() + " 200 " + candidate.getZ());
            } else {
                BlockPos ground = dryLand(mc, candidate);
                if (ground == null) {
                    log("no dry land loaded around " + candidate.getX() + "," + candidate.getZ() + ", trying elsewhere");
                } else {
                    cmd(mc, "give @s townstead:town_hall");
                    cmd(mc, "tp @s " + ground.getX() + " " + (ground.getY() + 1) + " " + (ground.getZ() + 3));
                    after(20, () -> {
                        mc.player.getInventory().setSelectedSlot(findHall(mc));
                        var r = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                                new BlockHitResult(Vec3.atCenterOf(ground).add(0, 0.5, 0), Direction.UP, ground, false));
                        log("placed town hall at " + ground.above() + ": " + r);
                    });
                    after(80, () -> mine(mc).ifPresentOrElse(c -> { founded = true; foundAt = t; log("CHECK founded " + c.name() + " PASS"); },
                            () -> log("founding at " + ground + " didn't take")));
                }
            }
            foundTries++;
            return;
        }
        if (founded && foundAt >= 0 && t - foundAt == 20) {
            GRIEFING = once("griefer");
            log(GRIEFING ? "starting the griefer checks" : "griefer checks done in an earlier session");
        }
        // Before he's anyone's co-mayor: what a griefer would try. None of it may change Alice's town.
        if (founded && shared.isPresent() && (GRIEFING || t >= 9000)) griefer(mc, shared.get());
        // On a signal from the soak script: knock down a few homes so new ones go up, then he's logged out mid-build.
        if (founded && t % 100 == 0 && new java.io.File("build-now.flag").exists()) {
            new java.io.File("build-now.flag").delete();
            mine(mc).ifPresent(c -> {
                plan(true);
                int n = 0;
                for (Building b : ClientLand.get().buildingsOf(c.id())) {
                    if (n >= 4 || b.state != Building.State.ACTIVE || !b.zone.residential()) continue;
                    send(new LandEditPayloads.BulldozeBuilding(c.id(), b.id));
                    n++;
                }
                for (Material m : new Material[] {Material.LOGS, Material.PLANKS, Material.COBBLESTONE, Material.GLASS}) {
                    send(new EconomyPayloads.Edit(c.id(), EconomyPayloads.Action.BUY, m.ordinal(), 64));
                }
                log("knocked down " + n + " homes and bought materials, for sites to be building when he logs out");
            });
        }
        if (founded && !laidOut && t - foundAt >= 900) { mine(mc).ifPresent(c -> layOut(mc, c)); laidOut = true; return; }
        if (founded && laidOut && t % 400 == 200) {
            // Every fifth minute for two minutes, he helps run Alice's town (once she's made him co-mayor).
            boolean helping = t > 6000 && (t / 1200) % 5 == 1;
            if (helping && shared.isPresent()) act(mc, shared.get());
            else mine(mc).ifPresent(c -> act(mc, c));
        }
    }

    /** A dry, loaded, fairly level spot near {@code around}, or null. */
    static BlockPos dryLand(Minecraft mc, BlockPos around) {
        for (int r = 0; r <= 48; r += 8) {
            for (int dx = -r; dx <= r; dx += 8) for (int dz = -r; dz <= r; dz += 8) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                int x = around.getX() + dx, z = around.getZ() + dz;
                if (!mc.level.isLoaded(new BlockPos(x, 64, z))) continue;
                int y = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos p = new BlockPos(x, y, z);
                if (!mc.level.getFluidState(p).isEmpty() || mc.level.getBlockState(p).isAir()) continue;
                boolean level = true;
                for (int ox = -6; ox <= 6 && level; ox += 3) for (int oz = -6; oz <= 6 && level; oz += 3) {
                    int h = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + ox, z + oz) - 1;
                    if (Math.abs(h - y) > 3 || !mc.level.getFluidState(new BlockPos(x + ox, h, z + oz)).isEmpty()) level = false;
                }
                if (level) return p;
            }
        }
        return null;
    }

    static int findHall(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem().toString().contains("town_hall")) return i;
        }
        return 0;
    }

    static void griefer(Minecraft mc, CitySummary alice) {
        UUID id = alice.id();
        int g = t - foundAt;
        if (GRIEFING && g == 100) plan(true);
        if (GRIEFING && g == 160) {
            Building target = ClientLand.get().buildingsOf(id).stream().filter(b -> b.state == Building.State.ACTIVE).findFirst().orElse(null);
            if (target == null) return;
            send(new LandEditPayloads.BulldozeBuilding(id, target.id));
            after(80, () -> check("bulldoze in a town he doesn't run refused", ClientLand.get().building(target.id) != null));
        }
        if (GRIEFING && g == 260) {
            int before = alice.budget().tax(0);
            send(new EconomyPayloads.Edit(id, EconomyPayloads.Action.TAX, 0, before + 3));
            after(80, () -> check("tax change in a town he doesn't run refused", byName(SHARED_TOWN).get().budget().tax(0) == before));
        }
        if (GRIEFING && g == 360) {
            send(new RenameTownPayload(id, "Bobsville"));
            after(80, () -> check("renaming a town he doesn't run refused", byName(SHARED_TOWN).isPresent()));
        }
        if (GRIEFING && g == 460) mine(mc).ifPresent(c -> {
            int before = segments(c.id());
            send(new BuildRoadPayload(c.id(), RoadType.LANE, new RoadCurve(Double.NaN, 0, Double.NaN, 0, Double.NaN, 0)));
            send(new BuildRoadPayload(c.id(), RoadType.LANE, new RoadCurve(c.townHall().getX(), c.townHall().getZ(), Double.POSITIVE_INFINITY, 0,
                    c.townHall().getX() + 20, c.townHall().getZ())));
            after(80, () -> check("NaN and infinite roads ignored", segments(c.id()) == before));
        });
        if (GRIEFING && g == 560) {
            send(new StorehousePayloads.Withdraw(new BlockPos(2_000_000, 64, 2_000_000), Identifier.withDefaultNamespace("cobblestone"), 64));
            send(new EconomyPayloads.Withdraw(new BlockPos(-2_000_000, 64, 2_000_000), 64));
            send(new StorehousePayloads.DepositAll(new BlockPos(2_000_000, 64, -2_000_000)));
            log("sent far-off block screen payloads (the server must not load those chunks)");
        }
        if (GRIEFING && g == 660) mine(mc).ifPresent(c -> {
            // A flood: 300 tax edits in one tick. The first 40 tokens' worth go through, the rest are dropped.
            for (int i = 0; i < 300; i++) send(new EconomyPayloads.Edit(c.id(), EconomyPayloads.Action.TAX, 0, c.budget().tax(0)));
            log("sent a flood of 300 edits");
        });
        if (GRIEFING && g == 750) {
            // After the flood has drained: an honest edit goes through again.
            mine(mc).ifPresent(c -> {
                int before = c.budget().tax(1);
                send(new EconomyPayloads.Edit(c.id(), EconomyPayloads.Action.TAX, 1, before + 1));
                after(60, () -> check("edits accepted again after the flood", mine(mc).get().budget().tax(1) == before + 1));
            });
        }
        // Once co-mayor: Alice's tax moves when he moves it, and her loans stay hers.
        if (t == 9000) {
            int before = alice.budget().tax(2);
            send(new EconomyPayloads.Edit(id, EconomyPayloads.Action.TAX, 2, before + 1));
            after(80, () -> {
                int now = byName(SHARED_TOWN).get().budget().tax(2);
                check("co-mayor's tax change accepted", now == before + 1);
                send(new EconomyPayloads.Edit(id, EconomyPayloads.Action.TAX, 2, before));
            });
            send(new EconomyPayloads.Edit(id, EconomyPayloads.Action.TAKE_LOAN, 0, 0));
            log("asked for a loan for Alice's town (only the mayor may: expect a refusal)");
        }
    }

    /** Bob's first streets: a cross of lanes from the Town Hall, zoned, with a windmill and a well. */
    static void layOut(Minecraft mc, CitySummary c) {
        plan(true);
        int x = c.townHall().getX(), z = c.townHall().getZ();
        int[][] arms = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        int i = 0;
        for (int[] a : arms) {
            int sx = x + a[0] * 6, sz = z + a[1] * 6;
            after(40 + 30 * i++, () -> send(new BuildRoadPayload(c.id(), RoadType.LANE, RoadCurve.straight(sx, sz, sx + a[0] * 48, sz + a[1] * 48))));
        }
        after(260, () -> zone(mc, c, ZoneType.RESIDENTIAL, 24));
        after(300, () -> zone(mc, c, ZoneType.COMMERCIAL, 4));
        after(340, () -> zone(mc, c, ZoneType.INDUSTRIAL, 6));
        after(380, () -> service(c, ServiceType.WINDMILL));
        after(420, () -> service(c, ServiceType.WELL));
        log("laid out streets for " + c.name());
    }

    // ---------------------------------------------------------------- edits a mayor makes

    static void act(Minecraft mc, CitySummary c) {
        if (!PlannerClient.isActive()) { plan(true); return; }
        if (undoNext) { send(new HistoryPayload(c.id(), true)); undoNext = false; log("redo in " + c.name()); return; }
        int pick = RNG.nextInt(11);
        switch (pick) {
            case 0 -> {
                int kind = RNG.nextInt(4), v = c.budget().tax(kind) + (RNG.nextBoolean() ? 1 : -1);
                send(new EconomyPayloads.Edit(c.id(), EconomyPayloads.Action.TAX, kind, v));
                log("tax " + kind + " -> " + v + " in " + c.name());
            }
            case 1 -> {
                int dep = RNG.nextInt(ServiceType.Department.values().length);
                int v = Math.max(50, Math.min(150, c.budget().budgets[dep] + (RNG.nextBoolean() ? 10 : -10)));
                send(new EconomyPayloads.Edit(c.id(), EconomyPayloads.Action.BUDGET, dep, v));
                log("budget " + dep + " -> " + v + " in " + c.name());
            }
            case 2, 3 -> zone(mc, c, List.of(ZoneType.RESIDENTIAL, ZoneType.COMMERCIAL, ZoneType.INDUSTRIAL).get(RNG.nextInt(3)), 6);
            case 4 -> {
                List<Long> empty = new ArrayList<>();
                LandData land = ClientLand.get();
                for (var e : land.zones().entrySet()) if (e.getValue().city().equals(c.id()) && land.buildingAt(e.getKey()) == null) empty.add(e.getKey());
                if (empty.isEmpty()) return;
                java.util.Collections.shuffle(empty, RNG);
                send(new LandEditPayloads.Paint(c.id(), -1, List.copyOf(empty.subList(0, Math.min(3, empty.size())))));
                log("cleared " + Math.min(3, empty.size()) + " empty lots in " + c.name());
            }
            case 5, 6 -> road(c);
            case 7 -> { send(new HistoryPayload(c.id(), false)); undoNext = true; log("undo in " + c.name()); }
            case 8 -> service(c, ServiceType.PARK);
            case 9 -> {
                Material m = Material.values()[RNG.nextInt(Material.values().length)];
                send(new EconomyPayloads.Edit(c.id(), EconomyPayloads.Action.BUY, m.ordinal(), 16));
                log("buy 16 " + m + " for " + c.name());
            }
            case 10 -> {
                Policy p = Policy.values()[RNG.nextInt(Policy.values().length)];
                boolean on = !c.policies().contains(p);
                send(new DistrictPayloads.SetPolicy(c.id(), -1, p.ordinal(), on));
                log("policy " + p + (on ? " on" : " off") + " in " + c.name());
            }
        }
    }

    static void zone(Minecraft mc, CitySummary c, ZoneType zone, int max) {
        Map<Long, RoadRaster.Cell> roads = ZoneRules.roadColumns(ClientRoads.network(), c.id());
        Set<TilePos> tiles = Set.copyOf(c.tiles());
        LandData land = ClientLand.get();
        List<Long> cells = new ArrayList<>();
        for (TilePos tile : c.tiles()) {
            for (int cx = tile.x() * 8; cx < tile.x() * 8 + 8; cx++) for (int cz = tile.z() * 8; cz < tile.z() * 8 + 8; cz++) {
                long k = ZoneRules.cellKey(cx, cz);
                if (land.zone(k) == null && land.buildingAt(k) == null && ZoneRules.zonable(cx, cz, roads, tiles) != null) cells.add(k);
            }
        }
        if (cells.isEmpty()) { log("nowhere to zone in " + c.name()); return; }
        java.util.Collections.shuffle(cells, RNG);
        List<Long> stroke = List.copyOf(cells.subList(0, Math.min(max, cells.size())));
        send(new LandEditPayloads.Paint(c.id(), ZoneType.PAINTABLE.indexOf(zone), stroke));
        log("zoned " + stroke.size() + " " + zone + " in " + c.name());
    }

    static void service(CitySummary c, ServiceType s) {
        Map<Long, RoadRaster.Cell> roads = ZoneRules.roadColumns(ClientRoads.network(), c.id());
        Set<TilePos> tiles = Set.copyOf(c.tiles());
        LandData land = ClientLand.get();
        List<Long> cells = new ArrayList<>();
        for (TilePos tile : c.tiles()) {
            for (int cx = tile.x() * 8; cx < tile.x() * 8 + 8; cx++) for (int cz = tile.z() * 8; cz < tile.z() * 8 + 8; cz++) {
                long k = ZoneRules.cellKey(cx, cz);
                if (land.buildingAt(k) == null && ZoneRules.zonable(cx, cz, roads, tiles) != null) cells.add(k);
            }
        }
        if (cells.isEmpty()) return;
        long k = cells.get(RNG.nextInt(cells.size()));
        send(new LandEditPayloads.PlaceService(c.id(), s.ordinal(), k));
        log("placing " + s + " in " + c.name());
    }

    /** Extends a dead-end road of the town by 20-36 blocks, straight on. */
    static void road(CitySummary c) {
        RoadNetwork net = ClientRoads.network();
        List<RoadNode[]> ends = new ArrayList<>();
        for (RoadNode n : net.nodes()) {
            List<RoadSegment> at = net.segmentsAt(n.id());
            if (at.size() != 1 || !at.get(0).city().equals(c.id())) continue;
            RoadSegment s = at.get(0);
            RoadNode other = net.node(s.a() == n.id() ? s.b() : s.a());
            if (other != null) ends.add(new RoadNode[] {n, other});
        }
        if (ends.isEmpty()) return;
        RoadNode[] e = ends.get(RNG.nextInt(ends.size()));
        double dx = e[0].x() - e[1].x(), dz = e[0].z() - e[1].z(), len = Math.hypot(dx, dz);
        if (len < 1) return;
        double go = 20 + RNG.nextInt(17);
        send(new BuildRoadPayload(c.id(), RoadType.LANE, RoadCurve.straight(e[0].x(), e[0].z(), e[0].x() + dx / len * go, e[0].z() + dz / len * go)));
        log("road from " + Math.round(e[0].x()) + "," + Math.round(e[0].z()) + " in " + c.name());
    }

    // ---------------------------------------------------------------- helpers

    static void probe(Minecraft mc) {
        long time = mc.level.getGameTime();
        for (CitySummary c : ClientCityCache.all()) {
            System.out.println("[Sync] C t=" + time + " " + SyncProbe.line(c.id(), ClientLand.get().buildingsOf(c.id()), ClientRoads.network(),
                    new Civics(c.lines(), c.districts(), c.policies()), c.tiles().size(), c.treasury(), c.budget(), c.stock()));
        }
    }

    static Optional<CitySummary> mine(Minecraft mc) {
        UUID me = mc.player.getUUID();
        return ClientCityCache.all().stream().filter(c -> c.owner().equals(me)).findFirst();
    }

    static Optional<CitySummary> byName(String name) {
        return ClientCityCache.all().stream().filter(c -> c.name().equals(name)).findFirst();
    }

    static int segments(UUID city) {
        int n = 0;
        for (RoadSegment s : ClientRoads.network().segments()) if (s.city().equals(city)) n++;
        return n;
    }

    static void plan(boolean open) {
        if (PlannerClient.isActive() != open) send(new PlannerTogglePayload(open));
    }

    static void send(CustomPacketPayload payload) {
        ClientPacketDistributor.sendToServer(payload);
    }

    static void cmd(Minecraft mc, String command) {
        mc.getConnection().sendCommand(command);
        log("/" + command);
    }

    static void after(int ticks, Runnable r) {
        CHECKS.add(new Object[] {t + ticks, r});
    }

    static void check(String what, boolean ok) {
        log("CHECK " + what + " " + (ok ? "PASS" : "FAIL"));
    }

    static void log(String s) {
        String line = "[Bot] " + ROLE + " t=" + t + " " + s;
        System.out.println(line);
        try {
            if (log == null) log = new PrintWriter(new FileWriter("bot.log", true), true);
            log.println(line);
        } catch (java.io.IOException ignored) {}
    }

    private MultiplayerBot() {}
}
