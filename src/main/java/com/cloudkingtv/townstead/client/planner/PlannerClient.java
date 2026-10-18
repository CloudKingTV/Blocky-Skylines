package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.client.ClientCityCache;
import com.cloudkingtv.townstead.client.ClientRoads;
import com.cloudkingtv.townstead.network.PlannerMessagePayload;
import com.cloudkingtv.townstead.road.RoadSegment;
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
        ROADS.reset();
        exitRequested = false;
    }

    private PlannerClient() {}
}
