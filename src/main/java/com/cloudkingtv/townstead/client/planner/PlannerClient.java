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
    public static final int CLEAR_BRUSH = ZoneType.values().length;
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
