package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.city.TilePos;
import com.cloudkingtv.townstead.client.ClientRoads;
import com.cloudkingtv.townstead.client.ClientLand;
import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.service.Utilities;
import com.cloudkingtv.townstead.zone.ZoneRules;
import com.cloudkingtv.townstead.zone.ZoneType;
import com.cloudkingtv.townstead.road.RoadCurve;
import com.cloudkingtv.townstead.road.RoadNode;
import com.cloudkingtv.townstead.road.RoadPlan;
import com.cloudkingtv.townstead.road.RoadRaster;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/** Draws the town's tile borders, the zone-cell cursor and the road tools' ghosts on top of the terrain while planning. */
public final class PlannerWorldRenderer {
    private static final int BORDER = 0xFF7CE07C, CURSOR = 0xFFFFFFFF, SELECTED = 0xFFFFC94A;
    private static final double LIFT = 0.06;

    public static void submit(SubmitCustomGeometryEvent event) {
        if (!PlannerClient.isActive()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        CitySummary city = PlannerClient.city().orElse(null);
        if (level == null || city == null) return;

        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        float width = mc.gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth;
        PoseStack pose = event.getPoseStack();
        BlockPos hover = PlannerClient.hoverCell, selected = PlannerClient.selectedCell;
        // Gentle pulse so the selection reads as interactive.
        float pulse = 0.75f + 0.25f * Mth.sin((Util.getMillis() % 100000L) / 250f);

        event.getSubmitNodeCollector().submitCustomGeometry(pose, PlannerRenderTypes.OVERLAY_LINES, (p, buf) -> {
            Set<TilePos> owned = new HashSet<>(city.tiles());
            for (TilePos t : owned) {
                int x0 = t.minBlockX(), z0 = t.minBlockZ(), x1 = x0 + TilePos.SIZE, z1 = z0 + TilePos.SIZE;
                if (!owned.contains(t.offset(0, -1))) edgeX(p, buf, level, cam, x0, x1, z0, BORDER, width * 2.5f);
                if (!owned.contains(t.offset(0, 1))) edgeX(p, buf, level, cam, x0, x1, z1, BORDER, width * 2.5f);
                if (!owned.contains(t.offset(-1, 0))) edgeZ(p, buf, level, cam, z0, z1, x0, BORDER, width * 2.5f);
                if (!owned.contains(t.offset(1, 0))) edgeZ(p, buf, level, cam, z0, z1, x1, BORDER, width * 2.5f);
            }
            if (selected != null) cell(p, buf, level, cam, selected, scale(SELECTED, pulse), width * 2.5f);
            if (hover != null && !hover.equals(selected)) cell(p, buf, level, cam, hover, CURSOR, width * 2f);
            roadLines(p, buf, cam, city, width);
            if (PlannerScreen.landActive()) landForSale(p, buf, level, cam, city, width, pulse);
            landLines(p, buf, level, cam, city, width, pulse);
            serviceLines(p, buf, cam, city, width, pulse);
            if (PlannerScreen.transportActive() || PlannerScreen.infoActive() && view().equals("traffic")) transitLines(p, buf, cam, city, width, pulse);
        });
        event.getSubmitNodeCollector().submitCustomGeometry(pose, PlannerRenderTypes.OVERLAY_QUADS, (p, buf) -> {
            roadQuads(p, buf, cam, city, pulse);
            landQuads(p, buf, level, cam, city, pulse);
            serviceQuads(p, buf, level, cam, city, pulse);
            if (PlannerScreen.landActive()) {
                // A faint wash over every tile for sale, stronger (or red) under the cursor.
                TilePos hoverTile = PlannerClient.hoverTile;
                Set<TilePos> owned = new HashSet<>(city.tiles()), done = new HashSet<>();
                for (TilePos t : city.tiles()) {
                    for (TilePos n : new TilePos[] {t.offset(1, 0), t.offset(-1, 0), t.offset(0, 1), t.offset(0, -1)}) {
                        if (owned.contains(n) || !done.add(n) || n.equals(hoverTile) || PlannerScreen.landProblem(city, n) != null) continue;
                        tileQuads(p, buf, level, cam, n, ARGB.color(Math.round(0x14 + 0x10 * pulse), 255, 210, 90));
                    }
                }
                if (hoverTile != null && !owned.contains(hoverTile)) {
                    boolean ok = PlannerScreen.landProblem(city, hoverTile) == null;
                    tileQuads(p, buf, level, cam, hoverTile, ok ? ARGB.color(Math.round(0x40 + 0x30 * pulse), 255, 210, 90) : 0x50FF5050);
                }
            }
        });
    }

    // ---------------------------------------------------------------- land for sale

    private static void tileQuads(PoseStack.Pose p, VertexConsumer buf, ClientLevel level, Vec3 cam, TilePos tile, int color) {
        for (int cx = tile.minBlockX() / TilePos.CELL; cx < (tile.minBlockX() + TilePos.SIZE) / TilePos.CELL; cx++) {
            for (int cz = tile.minBlockZ() / TilePos.CELL; cz < (tile.minBlockZ() + TilePos.SIZE) / TilePos.CELL; cz++) {
                long key = ZoneRules.cellKey(cx, cz);
                cellQuad(p, buf, cam, key, cellY(level, key), color, 0);
            }
        }
    }

    /** Gold borders round the tiles next to the town that could be bought, dim gold when it can't afford them yet, red where it never can. */
    private static void landForSale(PoseStack.Pose p, VertexConsumer buf, ClientLevel level, Vec3 cam, CitySummary city, float w, float pulse) {
        Set<TilePos> owned = new HashSet<>(city.tiles()), done = new HashSet<>();
        for (TilePos t : owned) {
            for (TilePos n : new TilePos[] {t.offset(1, 0), t.offset(-1, 0), t.offset(0, 1), t.offset(0, -1)}) {
                if (owned.contains(n) || !done.add(n)) continue;
                String problem = PlannerScreen.landProblem(city, n);
                int c = problem == null ? scale(0xFFFFD25A, pulse) : problem.equals("land.townstead.too_poor") || problem.equals("land.townstead.need_milestone")
                        ? 0xFFB08A40 : 0xFFB04040;
                int x0 = n.minBlockX(), z0 = n.minBlockZ(), x1 = x0 + TilePos.SIZE, z1 = z0 + TilePos.SIZE;
                edgeX(p, buf, level, cam, x0, x1, z0, c, w * 2f);
                edgeX(p, buf, level, cam, x0, x1, z1, c, w * 2f);
                edgeZ(p, buf, level, cam, z0, z1, x0, c, w * 2f);
                edgeZ(p, buf, level, cam, z0, z1, x1, c, w * 2f);
            }
        }
    }

    // ---------------------------------------------------------------- zoning and buildings

    private static final java.util.Map<Long, Integer> CELL_Y = new java.util.HashMap<>();
    private static java.util.List<Long> candidates = java.util.List.of();
    private static @org.jspecify.annotations.Nullable Object candidateKey;
    private static long cellYAt;

    /** Typical ground height of a cell (cached; refreshed every few seconds as the town changes). */
    private static int cellY(ClientLevel level, long key) {
        if (Util.getMillis() - cellYAt > 3000) { CELL_Y.clear(); cellYAt = Util.getMillis(); }
        return CELL_Y.computeIfAbsent(key, k -> {
            int x0 = RoadRaster.keyX(k) * TilePos.CELL, z0 = RoadRaster.keyZ(k) * TilePos.CELL;
            int[] h = new int[16];
            int i = 0;
            for (int x = x0 + 1; x < x0 + 8; x += 2) for (int z = z0 + 1; z < z0 + 8; z += 2) h[i++] = ground(level, x, z);
            java.util.Arrays.sort(h);
            return h[8];
        });
    }

    /** Cells of the town that can be zoned (cached per road/land update). */
    private static java.util.List<Long> candidates(CitySummary city) {
        Object key = java.util.List.of(ClientRoads.version(), ClientLand.version(), city.id(), city.tiles().size());
        if (!key.equals(candidateKey)) {
            candidateKey = key;
            java.util.List<Long> out = new java.util.ArrayList<>();
            for (TilePos t : city.tiles()) {
                for (int cx = t.minBlockX() / TilePos.CELL; cx < (t.minBlockX() + TilePos.SIZE) / TilePos.CELL; cx++) {
                    for (int cz = t.minBlockZ() / TilePos.CELL; cz < (t.minBlockZ() + TilePos.SIZE) / TilePos.CELL; cz++) {
                        if (PlannerClient.zonable(cx, cz) != null) out.add(ZoneRules.cellKey(cx, cz));
                    }
                }
            }
            candidates = out;
        }
        return candidates;
    }

    private static void landQuads(PoseStack.Pose p, VertexConsumer buf, ClientLevel level, Vec3 cam, CitySummary city, float pulse) {
        boolean zoning = PlannerScreen.zoningActive();
        var land = ClientLand.get();
        for (var e : land.zones().entrySet()) {
            if (!e.getValue().city().equals(city.id()) || land.buildingAt(e.getKey()) != null) continue;
            if (PlannerClient.STROKE.contains(e.getKey())) continue;
            cellQuad(p, buf, cam, e.getKey(), cellY(level, e.getKey()), ARGB.color(zoning ? 0x66 : 0x38, e.getValue().type().rgb), 0.5);
        }
        if (PlannerScreen.districtsActive()) {
            // Districts in their colours, the selected one brighter.
            var selected = DistrictTool.selected(city);
            for (var d : city.districts()) {
                int alpha = selected != null && selected.id() == d.id() ? Math.round(0x60 + 0x30 * pulse) : 0x48;
                for (long key : d.cells()) if (!PlannerClient.STROKE.contains(key)) cellQuad(p, buf, cam, key, cellY(level, key) + 1, ARGB.color(alpha, d.rgb()), 0);
            }
        }
        int brush = PlannerClient.zoneBrush;
        int strokeColor = PlannerScreen.districtsActive() ? (DistrictTool.mode == 2 ? 0x80FF5050 : 0x90FFFFFF)
                : brush < ZoneType.PAINTABLE.size() ? ARGB.color(0x99, ZoneType.PAINTABLE.get(brush).rgb) : 0x70FF5050;
        for (long key : PlannerClient.STROKE) cellQuad(p, buf, cam, key, cellY(level, key), strokeColor, 0.3);
        Building doomed = PlannerScreen.bulldozing() ? PlannerClient.hoveredBuilding() : null;
        if (doomed != null) {
            for (long k : doomed.cellKeys()) cellQuad(p, buf, cam, k, doomed.baseY - 1, ARGB.color(Math.round(0x50 + 0x40 * pulse), 255, 70, 60), 0);
        }
    }

    private static void landLines(PoseStack.Pose p, VertexConsumer buf, ClientLevel level, Vec3 cam, CitySummary city, float w, float pulse) {
        if (PlannerScreen.zoningActive()) {
            var land = ClientLand.get();
            for (long key : candidates(city)) {
                if (land.zone(key) != null || land.buildingAt(key) != null) continue;
                cellOutline(p, buf, cam, key, cellY(level, key) + 1.04, 0x90FFFFFF, w * 1.2f, 0.6);
            }
        }
        for (Building b : ClientLand.get().buildingsOf(city.id())) {
            if (b.state == Building.State.ACTIVE) continue;
            int color = b.state == Building.State.PAUSED ? scale(0xFFFF6B5B, pulse) : 0xFFFFD36B;
            lotOutline(p, buf, cam, b, b.baseY + 0.05, color, w * 2.5f);
        }
    }

    private static void cellQuad(PoseStack.Pose p, VertexConsumer buf, Vec3 cam, long key, int y, int color, double inset) {
        double x0 = RoadRaster.keyX(key) * TilePos.CELL + inset, z0 = RoadRaster.keyZ(key) * TilePos.CELL + inset;
        double x1 = x0 + TilePos.CELL - 2 * inset, z1 = z0 + TilePos.CELL - 2 * inset;
        float fy = (float) (y + 1.03 - cam.y);
        buf.addVertex(p, (float) (x0 - cam.x), fy, (float) (z0 - cam.z)).setColor(color);
        buf.addVertex(p, (float) (x0 - cam.x), fy, (float) (z1 - cam.z)).setColor(color);
        buf.addVertex(p, (float) (x1 - cam.x), fy, (float) (z1 - cam.z)).setColor(color);
        buf.addVertex(p, (float) (x1 - cam.x), fy, (float) (z0 - cam.z)).setColor(color);
    }

    /** The outline of a whole lot (one or more cells). */
    private static void lotOutline(PoseStack.Pose p, VertexConsumer buf, Vec3 cam, Building b, double y, int color, float w) {
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (long k : b.cellKeys()) {
            x0 = Math.min(x0, RoadRaster.keyX(k)); z0 = Math.min(z0, RoadRaster.keyZ(k));
            x1 = Math.max(x1, RoadRaster.keyX(k)); z1 = Math.max(z1, RoadRaster.keyZ(k));
        }
        double ax = x0 * TilePos.CELL + 0.2, az = z0 * TilePos.CELL + 0.2, bx = (x1 + 1) * TilePos.CELL - 0.2, bz = (z1 + 1) * TilePos.CELL - 0.2;
        line(p, buf, cam, ax, y, az, bx, y, az, color, w);
        line(p, buf, cam, bx, y, az, bx, y, bz, color, w);
        line(p, buf, cam, bx, y, bz, ax, y, bz, color, w);
        line(p, buf, cam, ax, y, bz, ax, y, az, color, w);
    }

    private static void cellOutline(PoseStack.Pose p, VertexConsumer buf, Vec3 cam, long key, double y, int color, float w, double inset) {
        double x0 = RoadRaster.keyX(key) * TilePos.CELL + inset, z0 = RoadRaster.keyZ(key) * TilePos.CELL + inset;
        double x1 = x0 + TilePos.CELL - 2 * inset, z1 = z0 + TilePos.CELL - 2 * inset;
        line(p, buf, cam, x0, y, z0, x1, y, z0, color, w);
        line(p, buf, cam, x1, y, z0, x1, y, z1, color, w);
        line(p, buf, cam, x1, y, z1, x0, y, z1, color, w);
        line(p, buf, cam, x0, y, z1, x0, y, z0, color, w);
    }

    // ---------------------------------------------------------------- services and info views

    private static final int GOOD = 0x9E9EE37D, BAD = 0x9EFF6B5B, SUPPLY = 0xB060B8FF;
    private static final int[] LEVEL_COLORS = {0xA0BFE3FF, 0xA06EA8FF, 0xA02A5FE0}; // deeper blue per level

    /** The lot ghost while placing, and the info tool's colouring of every building. */
    private static void serviceQuads(PoseStack.Pose p, VertexConsumer buf, ClientLevel level, Vec3 cam, CitySummary city, float pulse) {
        BlockPos hover = PlannerClient.hoverCell;
        if (PlannerScreen.placingActive() && hover != null) {
            int cx = Math.floorDiv(hover.getX(), TilePos.CELL), cz = Math.floorDiv(hover.getZ(), TilePos.CELL);
            long key = ZoneRules.cellKey(cx, cz);
            boolean ok = PlannerScreen.placeProblem(cx, cz) == null;
            Building lot = ok ? PlannerClient.lotFor(city, PlannerClient.placing, cx, cz) : null;
            int color = ok ? ARGB.color(Math.round(0x50 + 0x30 * pulse), 120, 230, 110) : 0x80FF5050;
            if (lot == null) cellQuad(p, buf, cam, key, cellY(level, key), color, 0.2);
            else for (long k : lot.cellKeys()) cellQuad(p, buf, cam, k, cellY(level, k), color, 0.1);
        }
        if (!PlannerScreen.infoActive()) return;
        String view = view();
        var util = PlannerClient.utilities();
        var model = PlannerClient.model();
        if (model == null) return;
        if (view.equals("land_value") || view.equals("pollution") || view.equals("noise")) {
            fieldQuads(p, buf, level, cam, city, model, view);
            return;
        }
        if (view.equals("traffic")) {
            // Every town road by how loaded it is: green clear, yellow busy, red jammed.
            for (RoadSegment s : ClientRoads.network().segments()) {
                if (!s.city().equals(city.id())) continue;
                int color = gradient(1f - (float) Math.min(1, model.traffic.ratio(s) / 1.2), 0xA0);
                for (RoadRaster.Cell c : ClientRoads.network().raster(s).values()) top(p, buf, cam, c.x(), c.y() + 1.04, c.z(), color);
            }
            return;
        }
        ServiceType service = ServiceType.find(view);
        for (Building b : ClientLand.get().buildingsOf(city.id())) {
            int color;
            if (view.equals("happiness") || view.equals("education")) {
                if (b.civic() || !b.working()) continue;
                if (view.equals("happiness")) color = gradient(model.happiness(b).total() / 100f, 0xA0);
                else if (!b.zone.residential()) continue;
                else color = LEVEL_COLORS[Math.max(0, Math.min(2, (int) Math.floor(model.education(b) * 1.5)))];
            } else if (b.civic()) {
                ServiceType s = b.serviceType();
                boolean supplies = switch (view) {
                    case "power" -> s.kind == ServiceType.Kind.POWER;
                    case "water" -> s.kind == ServiceType.Kind.WATER;
                    default -> s == service;
                };
                if (!supplies) continue;
                color = SUPPLY;
            } else if (view.equals("level")) {
                color = LEVEL_COLORS[Math.max(0, Math.min(2, b.level - 1))];
            } else if (b.state == Building.State.ABANDONED) {
                color = 0x90606060;
            } else {
                boolean has = switch (view) {
                    case "power" -> util.power(b);
                    case "water" -> util.water(b);
                    default -> service != null && model.covered(b, service);
                };
                color = has ? GOOD : BAD;
            }
            for (long k : b.cellKeys()) cellQuad(p, buf, cam, k, b.baseY - 1, color, 0.3);
        }
    }

    /** Pollution, noise or land value over every cell of the town, as a translucent heat map. */
    private static void fieldQuads(PoseStack.Pose p, VertexConsumer buf, ClientLevel level, Vec3 cam, CitySummary city,
                                   com.cloudkingtv.townstead.sim.TownModel model, String view) {
        for (TilePos t : city.tiles()) {
            for (int cx = t.minBlockX() / TilePos.CELL; cx < (t.minBlockX() + TilePos.SIZE) / TilePos.CELL; cx++) {
                for (int cz = t.minBlockZ() / TilePos.CELL; cz < (t.minBlockZ() + TilePos.SIZE) / TilePos.CELL; cz++) {
                    long key = ZoneRules.cellKey(cx, cz);
                    int color;
                    switch (view) {
                        case "land_value" -> color = gradient(model.fields.landValue(cx, cz) / 100f, 0x70);
                        case "pollution" -> {
                            int v = model.fields.pollution(cx, cz);
                            if (v <= 0) continue;
                            color = ARGB.color(Math.min(0xB0, 0x30 + v * 2), 120, 90, 40);
                        }
                        default -> {
                            int v = model.fields.noise(cx, cz);
                            if (v <= 0) continue;
                            color = ARGB.color(Math.min(0xB0, 0x30 + v * 3), 190, 80, 220);
                        }
                    }
                    cellQuad(p, buf, cam, key, cellY(level, key), color, 0.1);
                }
            }
        }
    }

    /** Red (0) through yellow to green (1). */
    private static int gradient(float f, int alpha) {
        f = Mth.clamp(f, 0f, 1f);
        int r = f < 0.5f ? 230 : Math.round(230 - (f - 0.5f) * 2 * 140);
        int g = f < 0.5f ? Math.round(70 + f * 2 * 150) : 220;
        return ARGB.color(alpha, r, g, 70);
    }

    /** Coverage rings: the hovered lot's while placing a service, and every service of the viewed type. */
    private static void serviceLines(PoseStack.Pose p, VertexConsumer buf, Vec3 cam, CitySummary city, float w, float pulse) {
        BlockPos hover = PlannerClient.hoverCell;
        ServiceType shown = null;
        if (PlannerScreen.placingActive()) {
            shown = PlannerClient.placing;
            if (hover != null && shown.kind == ServiceType.Kind.SERVICE) {
                int cx = Math.floorDiv(hover.getX(), TilePos.CELL), cz = Math.floorDiv(hover.getZ(), TilePos.CELL);
                var m = PlannerClient.model();
                ring(p, buf, cam, cx * TilePos.CELL + 4, cz * TilePos.CELL + 4, m == null ? shown.radius : m.radius(shown), scale(0xFFFFFFFF, pulse), w * 2.5f);
            }
        } else if (PlannerScreen.infoActive()) {
            shown = ServiceType.find(view());
        }
        if (shown == null || shown.kind != ServiceType.Kind.SERVICE) return;
        for (Building b : ClientLand.get().buildingsOf(city.id())) {
            if (!b.civic() || b.serviceType() != shown) continue;
            int color = b.state == Building.State.ACTIVE ? 0xFF6EC8FF : 0xFFFFD36B; // blue, or amber while building
            var m = PlannerClient.model();
            ring(p, buf, cam, b.centerX(), b.centerZ(), m == null ? shown.radius : m.radius(shown), color, w * 2f);
        }
    }

    private static String view() {
        var views = PlannerClient.INFO_VIEWS;
        return views.get(Math.max(0, Math.min(views.size() - 1, PlannerClient.infoView)));
    }

    /** A circle that follows the ground. */
    private static void ring(PoseStack.Pose p, VertexConsumer buf, Vec3 cam, double x, double z, double r, int color, float w) {
        int n = Math.max(32, (int) (r * 1.5));
        double px = x + r, pz = z, py = groundY(px, pz) + LIFT;
        for (int i = 1; i <= n; i++) {
            double a = Math.PI * 2 * i / n;
            double qx = x + r * Math.cos(a), qz = z + r * Math.sin(a), qy = groundY(qx, qz) + LIFT;
            line(p, buf, cam, px, py, pz, qx, qy, qz, color, w);
            px = qx; py = qy; pz = qz;
        }
    }

    // ---------------------------------------------------------------- roads

    private static final int GHOST_ROAD = 0x7050B4FF, GHOST_WALK = 0x7090D4FF, GHOST_MEDIAN = 0x7070E070,
            GHOST_BAD = 0x70FF5050, GHOST_SHORT = 0x70FFAA40, NODE = 0xFF9BE7FF, LAMP = 0xFFFFD36B;

    private static void roadQuads(PoseStack.Pose p, VertexConsumer buf, Vec3 cam, CitySummary city, float pulse) {
        RoadTool.Preview preview = PlannerClient.ROADS.drawing() ? PlannerClient.ROADS.preview(city) : null;
        if (preview != null) {
            boolean ok = preview.plan().ok();
            for (RoadPlan.Column col : preview.plan().columns.values()) {
                RoadRaster.Cell c = col.cell();
                int color = !ok ? GHOST_BAD : !preview.missing().isEmpty() ? GHOST_SHORT : switch (c.part()) {
                    case CARRIAGEWAY -> GHOST_ROAD;
                    case SIDEWALK -> GHOST_WALK;
                    case MEDIAN -> GHOST_MEDIAN;
                };
                top(p, buf, cam, c.x(), c.y() + 1.03, c.z(), color);
            }
        }
        RoadSegment doomed = PlannerClient.hoveredSegment();
        if (doomed != null) {
            int red = ARGB.color(Math.round(0x50 + 0x40 * pulse), 255, 70, 60);
            for (RoadRaster.Cell c : ClientRoads.network().raster(doomed).values()) top(p, buf, cam, c.x(), c.y() + 1.03, c.z(), red);
        }
    }

    private static void top(PoseStack.Pose p, VertexConsumer buf, Vec3 cam, int x, double y, int z, int color) {
        float fy = (float) (y - cam.y);
        float x0 = (float) (x - cam.x), x1 = x0 + 1, z0 = (float) (z - cam.z), z1 = z0 + 1;
        buf.addVertex(p, x0, fy, z0).setColor(color);
        buf.addVertex(p, x0, fy, z1).setColor(color);
        buf.addVertex(p, x1, fy, z1).setColor(color);
        buf.addVertex(p, x1, fy, z0).setColor(color);
    }

    private static void roadLines(PoseStack.Pose p, VertexConsumer buf, Vec3 cam, CitySummary city, float width) {
        RoadTool roads = PlannerClient.ROADS;
        if (!roads.drawing() && roads.cursor == null) return;
        // Nodes you can join, as small diamonds.
        for (RoadNode n : ClientRoads.network().nodes()) {
            if (Math.abs(n.x() - cam.x) > 160 || Math.abs(n.z() - cam.z) > 160) continue;
            diamond(p, buf, cam, n.x(), n.y() + 1.05, n.z(), 1.2, NODE, width * 1.5f);
        }
        RoadTool.Preview preview = roads.drawing() ? roads.preview(city) : null;
        if (preview != null) {
            RoadCurve c = preview.curve();
            int steps = Math.max(4, (int) Math.ceil(c.length()));
            int color = preview.buildable() ? 0xFFFFFFFF : 0xFFFF8070;
            double px = c.x(0), pz = c.z(0), py = preview.piece().heightAt(0) + 1.1;
            for (int i = 1; i <= steps; i++) {
                double t = (double) i / steps, x = c.x(t), z = c.z(t), y = preview.piece().heightAt(t) + 1.1;
                line(p, buf, cam, px, py, pz, x, y, z, color, width * 2f);
                px = x; py = y; pz = z;
            }
            for (var list : preview.plan().decorations.values()) {
                for (RoadSegment.Decoration d : list) {
                    double x = d.pos().getX() + 0.5, z = d.pos().getZ() + 0.5, y = d.pos().getY();
                    double top = d.kind() == RoadSegment.DecorationKind.LAMP ? y + 3 : y + 1;
                    line(p, buf, cam, x, y, z, x, top, z, d.kind() == RoadSegment.DecorationKind.LAMP ? LAMP : GHOST_MEDIAN | 0xFF000000, width * 2f);
                }
            }
        }
        if (roads.mode == RoadTool.Mode.CURVE && roads.start != null && roads.control != null && roads.cursor != null) {
            double y = preview != null ? preview.piece().heightAt(0.5) + 1.1 : roads.start.y() != null ? roads.start.y() + 1.1 : cam.y - 20;
            line(p, buf, cam, roads.start.x(), y, roads.start.z(), roads.control.x(), y, roads.control.z(), 0x88FFFFFF, width);
            line(p, buf, cam, roads.control.x(), y, roads.control.z(), roads.cursor.x(), y, roads.cursor.z(), 0x88FFFFFF, width);
        }
        if (roads.cursor != null) {
            RoadTool.Snap s = roads.cursor;
            double y = s.y() != null ? s.y() + 1.05 : groundY(s.x(), s.z()) + 0.05;
            int color = switch (s.kind()) {
                case NODE -> NODE;
                case ROAD -> 0xFF9EE37D;
                case GRID, ANGLE -> 0xFFFFFFFF;
                case FREE -> 0xFFB0B0B0;
            };
            diamond(p, buf, cam, s.x(), y, s.z(), s.kind() == RoadTool.SnapKind.NODE ? 1.8 : 1.0, color, width * 2.5f);
        }
    }

    private static double groundY(double x, double z) {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 64 : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
    }

    // ---------------------------------------------------------------- coach lines

    /** Every coach line along its route in its colour with its stops, and the line being drawn. */
    private static void transitLines(PoseStack.Pose p, VertexConsumer buf, Vec3 cam, CitySummary city, float w, float pulse) {
        var model = PlannerClient.model();
        var hovered = PlannerScreen.transportActive() && LineTool.mode == 2 ? LineTool.hoveredLine(city) : null;
        int lane = 0;
        for (var line : city.lines()) {
            int color = 0xFF000000 | line.rgb();
            if (hovered != null && hovered.id() == line.id()) color = scale(0xFFFF5050, pulse);
            var stats = model == null ? null : model.traffic.line(line.id());
            // Lines sharing a road sit side by side, a little apart.
            double lift = 1.25 + 0.12 * (lane++ % 4);
            if (stats != null) legs(p, buf, cam, stats.route(), lift, color, w * 3.5f);
            for (var stop : line.stops()) diamond(p, buf, cam, stop.marker().getX() + 0.5, stop.marker().getY() + 0.3, stop.marker().getZ() + 0.5, 1.4, color, w * 3f);
        }
        if (PlannerScreen.transportActive() && LineTool.mode == 1) {
            int white = scale(0xFFFFFFFF, pulse);
            legs(p, buf, cam, LineTool.draftRoute(city), 1.5, white, w * 3f);
            for (var pick : LineTool.DRAFT) {
                Vec3 at = LineTool.point(pick);
                if (at != null) diamond(p, buf, cam, at.x, at.y + 0.3, at.z, 1.6, white, w * 3f);
            }
            var road = LineTool.hoverRoad(city);
            if (road != null) {
                var seg = ClientRoads.network().segment(road.segment());
                if (seg != null) diamond(p, buf, cam, seg.curve().x(road.t()), seg.heightAt(road.t()) + 1.3, seg.curve().z(road.t()), 1.2,
                        LineTool.onFirst() ? 0xFF9EE37D : 0xFFFFD36B, w * 2.5f);
            }
        }
    }

    /** A route drawn along the road centreline, {@code lift} blocks above the surface. */
    private static void legs(PoseStack.Pose p, VertexConsumer buf, Vec3 cam, java.util.List<com.cloudkingtv.townstead.road.RoadRouter.Leg> legs,
                             double lift, int color, float w) {
        for (var leg : legs) {
            RoadSegment s = ClientRoads.network().segment(leg.segment());
            if (s == null) continue;
            double span = Math.abs(leg.t1() - leg.t0());
            int n = Math.max(1, (int) Math.ceil(span * s.curve().length() / 2));
            double px = 0, py = 0, pz = 0;
            for (int i = 0; i <= n; i++) {
                double t = leg.t0() + (leg.t1() - leg.t0()) * i / n;
                double x = s.curve().x(t), y = s.heightAt(t) + lift, z = s.curve().z(t);
                if (i > 0) line(p, buf, cam, px, py, pz, x, y, z, color, w);
                px = x; py = y; pz = z;
            }
        }
    }

    private static void diamond(PoseStack.Pose p, VertexConsumer buf, Vec3 cam, double x, double y, double z, double r, int color, float w) {
        line(p, buf, cam, x - r, y, z, x, y, z - r, color, w);
        line(p, buf, cam, x, y, z - r, x + r, y, z, color, w);
        line(p, buf, cam, x + r, y, z, x, y, z + r, color, w);
        line(p, buf, cam, x, y, z + r, x - r, y, z, color, w);
    }

    /** A flat square at the cell's typical ground height, so trunks and boulders don't distort it. */
    private static void cell(PoseStack.Pose p, VertexConsumer buf, ClientLevel level, Vec3 cam, BlockPos cell, int color, float w) {
        int x0 = cell.getX(), z0 = cell.getZ(), x1 = x0 + TilePos.CELL, z1 = z0 + TilePos.CELL;
        int[] heights = new int[TilePos.CELL * TilePos.CELL];
        int i = 0;
        for (int x = x0; x < x1; x++) for (int z = z0; z < z1; z++) heights[i++] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        java.util.Arrays.sort(heights);
        double y = heights[heights.length / 2] + LIFT;
        line(p, buf, cam, x0, y, z0, x1, y, z0, color, w);
        line(p, buf, cam, x1, y, z0, x1, y, z1, color, w);
        line(p, buf, cam, x1, y, z1, x0, y, z1, color, w);
        line(p, buf, cam, x0, y, z1, x0, y, z0, color, w);
    }

    /** A line along the ground from (x0,z) to (x1,z), stepping one block at a time to follow the terrain. */
    private static void edgeX(PoseStack.Pose p, VertexConsumer buf, ClientLevel level, Vec3 cam, int x0, int x1, int z, int c, float w) {
        double prevY = surface(level, x0, z);
        for (int x = x0; x < x1; x++) {
            double y = surface(level, x, z);
            if (y != prevY) line(p, buf, cam, x, prevY, z, x, y, z, c, w);
            line(p, buf, cam, x, y, z, x + 1, y, z, c, w);
            prevY = y;
        }
    }

    private static void edgeZ(PoseStack.Pose p, VertexConsumer buf, ClientLevel level, Vec3 cam, int z0, int z1, int x, int c, float w) {
        double prevY = surface(level, x, z0);
        for (int z = z0; z < z1; z++) {
            double y = surface(level, x, z);
            if (y != prevY) line(p, buf, cam, x, prevY, z, x, y, z, c, w);
            line(p, buf, cam, x, y, z, x, y, z + 1, c, w);
            prevY = y;
        }
    }

    /**
     * Ground height (ignoring leaves) at a border point. Single-column spikes such as tree trunks are flattened to
     * their surroundings so borders hug the land rather than climbing every tree.
     */
    private static double surface(ClientLevel level, int x, int z) {
        int h = ground(level, x, z);
        int around = Math.max(Math.max(ground(level, x - 2, z), ground(level, x + 2, z)),
                Math.max(ground(level, x, z - 2), ground(level, x, z + 2)));
        return Math.min(h, around + 1) + LIFT;
    }

    private static int ground(ClientLevel level, int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    private static void line(PoseStack.Pose p, VertexConsumer buf, Vec3 cam, double ax, double ay, double az,
                             double bx, double by, double bz, int color, float w) {
        float nx = (float) (bx - ax), ny = (float) (by - ay), nz = (float) (bz - az);
        float len = Mth.sqrt(nx * nx + ny * ny + nz * nz);
        if (len == 0) return;
        nx /= len; ny /= len; nz /= len;
        buf.addVertex(p, (float) (ax - cam.x), (float) (ay - cam.y), (float) (az - cam.z)).setColor(color).setNormal(p, nx, ny, nz).setLineWidth(w);
        buf.addVertex(p, (float) (bx - cam.x), (float) (by - cam.y), (float) (bz - cam.z)).setColor(color).setNormal(p, nx, ny, nz).setLineWidth(w);
    }

    private static int scale(int argb, float f) {
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * f)), g = Math.min(255, (int) (((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((argb & 0xFF) * f));
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private PlannerWorldRenderer() {}
}
