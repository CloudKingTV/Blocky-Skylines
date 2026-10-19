package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.client.ClientRoads;
import com.cloudkingtv.townstead.network.TransitPayloads;
import com.cloudkingtv.townstead.road.RoadRouter;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.transit.CoachLine;
import com.cloudkingtv.townstead.transit.CoachLines;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The transport tool's line drawing, as in Cities: Skylines: click roads to drop stops in order, click the first stop
 * again (or press Enter) to close the loop; right-click takes the last stop back. Its other mode removes a line by
 * clicking one of its stops.
 */
public final class LineTool {
    /** 0: place the coach house, 1: draw a line, 2: remove a line. */
    public static int mode;
    public static final List<TransitPayloads.StopPick> DRAFT = new ArrayList<>();
    /** The point of ground under the cursor (line modes only). */
    static @Nullable Vec3 hover;
    /** Stops within this many blocks count as clicked. */
    private static final double PICK = 5;
    private static @Nullable Object routerKey;
    private static @Nullable RoadRouter router;

    static RoadRouter router(CitySummary city) {
        Object key = List.of(ClientRoads.version(), city.id());
        if (router == null || !key.equals(routerKey)) {
            routerKey = key;
            router = new RoadRouter(ClientRoads.network(), city.id());
        }
        return router;
    }

    static void reset() {
        DRAFT.clear();
        hover = null;
    }

    /** The town road under the cursor, as a stop would sit on it. */
    public static RoadRouter.@Nullable Access hoverRoad(CitySummary city) {
        return hover == null ? null : router(city).access(hover.x, hover.z, 3);
    }

    /** World point of a pick on its road. */
    static @Nullable Vec3 point(TransitPayloads.StopPick p) {
        RoadSegment s = ClientRoads.network().segment(p.segment());
        return s == null ? null : new Vec3(s.curve().x(p.t()), s.heightAt(p.t()) + 1, s.curve().z(p.t()));
    }

    /** Whether the cursor is on the first stop, closing the loop. */
    static boolean onFirst() {
        if (DRAFT.size() < 2 || hover == null) return false;
        Vec3 first = point(DRAFT.getFirst());
        return first != null && Math.hypot(first.x - hover.x, first.z - hover.z) <= PICK;
    }

    /** What clicking now would do wrong, or null. */
    static @Nullable Component problem(CitySummary city) {
        if (mode == 1) {
            if (onFirst()) return CoachLines.problem(city.id(), city.lines().size(), ClientRoads.network(), DRAFT);
            if (hoverRoad(city) == null) return Component.translatable("transit.townstead.not_road");
            if (DRAFT.size() >= CoachLines.MAX_STOPS) return Component.translatable("transit.townstead.too_long", CoachLines.MAX_STOPS);
        }
        if (mode == 2 && hoveredLine(city) == null) return Component.translatable("transit.townstead.pick_line");
        return null;
    }

    /** A click: drop a stop, close the loop, or remove the line under the cursor. Returns a message to flash, if any. */
    static @Nullable Component click(CitySummary city) {
        if (mode == 1) {
            if (onFirst()) return finish(city);
            RoadRouter.Access a = hoverRoad(city);
            if (a == null) return Component.translatable("transit.townstead.not_road");
            if (DRAFT.size() >= CoachLines.MAX_STOPS) return Component.translatable("transit.townstead.too_long", CoachLines.MAX_STOPS);
            DRAFT.add(new TransitPayloads.StopPick(a.segment(), a.t()));
            return null;
        }
        if (mode == 2) {
            CoachLine line = hoveredLine(city);
            if (line == null) return Component.translatable("transit.townstead.pick_line");
            ClientPacketDistributor.sendToServer(new TransitPayloads.RemoveLine(city.id(), line.id()));
        }
        return null;
    }

    /** Sends the drawn line to the server, or says why it can't be one. */
    static @Nullable Component finish(CitySummary city) {
        Component problem = CoachLines.problem(city.id(), city.lines().size(), ClientRoads.network(), DRAFT);
        if (problem != null) return problem;
        ClientPacketDistributor.sendToServer(new TransitPayloads.CreateLine(city.id(), List.copyOf(DRAFT)));
        DRAFT.clear();
        return null;
    }

    /** Takes back the last stop; false if there was none. */
    static boolean undo() {
        if (DRAFT.isEmpty()) return false;
        DRAFT.removeLast();
        return true;
    }

    /** The line with a stop under the cursor. */
    static @Nullable CoachLine hoveredLine(CitySummary city) {
        if (hover == null) return null;
        CoachLine best = null;
        double bestD = PICK;
        for (CoachLine line : city.lines()) {
            for (CoachLine.Stop s : line.stops()) {
                double d = Math.hypot(s.marker().getX() + 0.5 - hover.x, s.marker().getZ() + 0.5 - hover.z);
                if (d <= bestD) { bestD = d; best = line; }
            }
        }
        return best;
    }

    /** The draft's route so far: each leg between consecutive stops, and back to the first while hovering it. */
    static List<RoadRouter.Leg> draftRoute(CitySummary city) {
        RoadRouter r = router(city);
        List<RoadRouter.Leg> out = new ArrayList<>();
        List<RoadRouter.Access> stops = new ArrayList<>();
        for (TransitPayloads.StopPick p : DRAFT) stops.add(new RoadRouter.Access(p.segment(), p.t()));
        RoadRouter.Access next = mode == 1 ? hoverRoad(city) : null;
        if (onFirst()) next = stops.getFirst();
        if (next != null && !stops.isEmpty()) stops.add(next);
        for (int i = 0; i + 1 < stops.size(); i++) {
            List<RoadRouter.Leg> legs = r.route(stops.get(i), stops.get(i + 1), r::freeTime);
            if (legs != null) out.addAll(legs);
        }
        return out;
    }

    private LineTool() {}
}
