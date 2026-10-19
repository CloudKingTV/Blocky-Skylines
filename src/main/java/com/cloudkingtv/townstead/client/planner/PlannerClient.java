package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.client.ClientCityCache;
import com.cloudkingtv.townstead.client.ClientRoads;
import com.cloudkingtv.townstead.network.PlannerMessagePayload;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.road.RoadRaster;
import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.client.ClientLand;
import com.cloudkingtv.townstead.zone.ZoneRules;
import com.cloudkingtv.townstead.zone.ZoneType;
import com.cloudkingtv.townstead.network.PlannerStatePayload;
import com.cloudkingtv.townstead.network.PlannerTogglePayload;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/** Client-side planner state: whether it's open, which town, the camera, and what the cursor points at. */
public final class PlannerClient {
    public static final PlannerCamera CAMERA = new PlannerCamera();
    public static final RoadTool ROADS = new RoadTool();
    /** Road segment under the bulldozer, or -1. */
    public static int hoveredSegmentId = -1;
    /** Building under the cursor (inspect/bulldoze), or -1. */
    public static int hoveredBuildingId = -1;
    /** Zoning brush: a zone ordinal, or {@link #CLEAR_BRUSH}. */
    public static int zoneBrush = 0;
    public static final int CLEAR_BRUSH = ZoneType.PAINTABLE.size();
    /** Info view shown by the info tool: -1 none, else an index into {@link #INFO_VIEWS}. */
    public static int infoView = 0;
    /** Info views: power, water, happiness, the land fields, education, levels, then each service's coverage. */
    public static final java.util.List<String> INFO_VIEWS;
    static {
        java.util.List<String> views = new java.util.ArrayList<>(java.util.List.of("power", "water", "happiness", "land_value",
                "pollution", "noise", "education", "level"));
        for (com.cloudkingtv.townstead.service.ServiceType s : com.cloudkingtv.townstead.service.ServiceType.SERVICES) views.add(s.key());
        INFO_VIEWS = java.util.List.copyOf(views);
    }
    /** The service or utility the placing tools put down. */
    public static com.cloudkingtv.townstead.service.ServiceType placing = com.cloudkingtv.townstead.service.ServiceType.WINDMILL;
    private static @Nullable Object modelKey;
    private static com.cloudkingtv.townstead.sim.@Nullable TownModel model;

    /** The town's rules worked out for the town being planned, as the server works them (cached per update). */
    public static com.cloudkingtv.townstead.sim.@Nullable TownModel model() {
        CitySummary city = city().orElse(null);
        if (city == null) return null;
        Object key = java.util.List.of(ClientLand.version(), ClientRoads.version(), city);
        if (model == null || !key.equals(modelKey)) {
            modelKey = key;
            model = com.cloudkingtv.townstead.sim.TownModel.of(city.id(), ClientLand.get().buildingsOf(city.id()), ClientRoads.network(),
                    city.budget(), city.treasury(), city.tiles(), city.townHall());
        }
        return model;
    }

    /** Power, water and networks for the town being planned. */
    public static com.cloudkingtv.townstead.service.Utilities.State utilities() {
        var m = model();
        return m != null ? m.util : com.cloudkingtv.townstead.service.Utilities.compute(java.util.List.of(), ClientRoads.network());
    }

    /** Cells in the brush stroke being dragged (sent on release). */
    public static final java.util.LinkedHashSet<Long> STROKE = new java.util.LinkedHashSet<>();
    private static @Nullable Object frontageKey;
    private static java.util.Map<Long, RoadRaster.Cell> roadColumns = java.util.Map.of();
    private static @Nullable UUID cityId;
    /** Minimum corner of the 8x8 zone cell under the cursor (y = surface), or null. */
    public static @Nullable BlockPos hoverCell;
    public static @Nullable BlockPos selectedCell;
    private static boolean exitRequested;

    public static boolean isActive() {
        return cityId != null;
    }

    public static Optional<CitySummary> city() {
        return cityId == null ? Optional.empty() : ClientCityCache.byId(cityId);
    }

    public static void onState(PlannerStatePayload state) {
        Minecraft mc = Minecraft.getInstance();
        if (state.active()) {
            cityId = state.cityId();
            exitRequested = false;
            hoverCell = selectedCell = null;
            ROADS.reset();
            city().ifPresent(c -> CAMERA.reset(c.townHall().getX() + 0.5, c.townHall().getZ() + 0.5, 180f));
            mc.gui.setScreen(new PlannerScreen());
        } else {
            cityId = null;
            hoverCell = selectedCell = null;
            if (mc.gui.screen() instanceof PlannerScreen) mc.gui.setScreen(null);
        }
    }

    public static @Nullable RoadSegment hoveredSegment() {
        return hoveredSegmentId < 0 ? null : ClientRoads.network().segment(hoveredSegmentId);
    }

    public static @Nullable Building hoveredBuilding() {
        return hoveredBuildingId < 0 ? null : ClientLand.get().building(hoveredBuildingId);
    }

    /** The road-facing side of a cell if it can be zoned for this town (cached per road update). */
    public static net.minecraft.core.@Nullable Direction frontage(int cellX, int cellZ) {
        CitySummary city = city().orElse(null);
        if (city == null) return null;
        Object key = java.util.List.of(ClientRoads.version(), city.id(), city.tiles().size());
        if (!key.equals(frontageKey)) {
            frontageKey = key;
            roadColumns = ZoneRules.roadColumns(ClientRoads.network(), city.id());
        }
        return ZoneRules.frontage(cellX, cellZ, roadColumns, java.util.Set.copyOf(city.tiles()));
    }

    /** Whether a cell can be zoned: it faces a road or lies behind one that does (cached per road update). */
    public static net.minecraft.core.@Nullable Direction zonable(int cellX, int cellZ) {
        CitySummary city = city().orElse(null);
        if (city == null || frontage(cellX, cellZ) != null) return frontage(cellX, cellZ);
        return ZoneRules.zonable(cellX, cellZ, roadColumns, java.util.Set.copyOf(city.tiles()));
    }

    /**
     * Where a service of this type would stand if placed at the hovered cell: its anchor cell and frontage, with the
     * hovered cell anywhere along its front row. Null if it doesn't fit there.
     */
    public static @Nullable Building lotFor(CitySummary city, com.cloudkingtv.townstead.service.ServiceType service, int cx, int cz) {
        net.minecraft.core.Direction front = frontage(cx, cz);
        if (front == null) return null;
        net.minecraft.core.Direction right = front.getCounterClockWise();
        var land = ClientLand.get();
        java.util.Set<com.cloudkingtv.townstead.city.TilePos> tiles = java.util.Set.copyOf(city.tiles());
        for (int shift = 0; shift < service.wide; shift++) {
            int ax = cx - right.getStepX() * shift, az = cz - right.getStepZ() * shift;
            if (ZoneRules.lotFits(ax, az, front, service.wide, service.deep, roadColumns, tiles, k -> land.buildingAt(k) == null)) {
                Building b = new Building(-1, city.id(), ZoneType.CIVIC, ax, az, front, 0, 0L, 0L, ZoneRules.setback(ax, az, front, roadColumns));
                b.service = service.key();
                b.wide = service.wide;
                b.deep = service.deep;
                return b;
            }
        }
        return null;
    }

    /** A stand-in for the service the placing tool would put on a lot, to price it before the click. */
    public static @Nullable Building previewCivic(CitySummary city, com.cloudkingtv.townstead.service.ServiceType service, int cx, int cz) {
        Minecraft mc = Minecraft.getInstance();
        Building lot = lotFor(city, service, cx, cz);
        if (lot == null || mc.level == null) return null;
        int x = (int) lot.centerX(), z = (int) lot.centerZ();
        int y = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        Building b = new Building(-1, city.id(), ZoneType.CIVIC, lot.cellX, lot.cellZ, lot.front, y, 0L, 0L, lot.setback);
        b.service = service.key();
        b.wide = lot.wide;
        b.deep = lot.deep;
        b.style = com.cloudkingtv.townstead.building.Palette.styleAt(mc.level, new BlockPos(x, y, z));
        return b;
    }

    public static void onMessage(PlannerMessagePayload msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof PlannerScreen screen) screen.flash(msg.message(), msg.ok());
        else if (mc.player != null) mc.player.sendOverlayMessage(msg.message());
    }

    public static void requestOpen() {
        ClientPacketDistributor.sendToServer(new PlannerTogglePayload(true));
    }

    public static void requestExit() {
        if (exitRequested) return;
        exitRequested = true;
        ClientPacketDistributor.sendToServer(new PlannerTogglePayload(false));
    }

    public static void tick(Minecraft mc) {
        if (!isActive() || mc.player == null) return;
        Optional<CitySummary> city = city();
        if (city.isEmpty()) { // town disbanded while planning
            requestExit();
            return;
        }
        CAMERA.tick(mc.player, city.get());
        // Another screen (chat, pause menu) took over and then closed: bring the planner back.
        if (mc.gui.screen() == null && !exitRequested) mc.gui.setScreen(new PlannerScreen());
    }

    public static void reset() {
        cityId = null;
        hoverCell = selectedCell = null;
        hoveredSegmentId = -1;
        hoveredBuildingId = -1;
        STROKE.clear();
        ROADS.reset();
        exitRequested = false;
    }

    private PlannerClient() {}
}
