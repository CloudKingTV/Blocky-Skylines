package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.city.Stock;
import com.cloudkingtv.townstead.client.ClientRoads;
import com.cloudkingtv.townstead.road.Material;
import com.cloudkingtv.townstead.road.RoadCurve;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.road.RoadGrading;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadNode;
import com.cloudkingtv.townstead.road.RoadPlan;
import com.cloudkingtv.townstead.road.RoadRaster;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.road.RoadType;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Client state of the road tools: the chosen road type and mode, the points clicked so far, where the cursor snaps,
 * and a live plan of the road being drawn (ghost, cost and problems). Mirrors Cities: Skylines: click to start,
 * click to finish (curves take a middle click to bend), and the next road starts where the last one ended.
 */
public final class RoadTool {
    public enum Mode { STRAIGHT, CURVE }

    public enum SnapKind { FREE, GRID, ANGLE, NODE, ROAD }

    /** A cursor position after snapping. {@code y} is pinned when joining an existing road. */
    public record Snap(double x, double z, @Nullable Integer y, SnapKind kind) {}

    /** What the preview shows: the graded piece, its plan, and whether the town can pay for it. */
    public record Preview(RoadCurve curve, RoadSegment piece, RoadPlan plan, Map<Material, Integer> missing) {
        public boolean buildable() { return plan.ok() && missing.isEmpty(); }
    }

    private static final double GRID = 8, ANGLE_STEP = 15, ANGLE_SNAP = 4;

    public RoadType type = RoadType.STREET;
    public Mode mode = Mode.STRAIGHT;
    public @Nullable Snap start, control, cursor;
    private @Nullable Preview preview;
    private @Nullable Object previewKey;

    public boolean drawing() {
        return start != null;
    }

    /** Escape/right-click: drop the road being drawn, as in Cities: Skylines. Returns false if there was none. */
    public boolean cancel() {
        if (start == null) return false;
        start = control = null;
        return true;
    }

    public void reset() {
        start = control = cursor = null;
        preview = null;
        previewKey = null;
    }

    // ---------------------------------------------------------------- snapping

    public void hover(double x, double z, boolean free) {
        cursor = snap(x, z, free);
    }

    public Snap snap(double x, double z, boolean free) {
        RoadNetwork net = ClientRoads.network();
        Optional<RoadNode> node = net.nearestNode(x, z, RoadEditor.NODE_SNAP);
        if (node.isPresent()) return new Snap(node.get().x(), node.get().z(), node.get().y(), SnapKind.NODE);
        Optional<RoadNetwork.SegmentHit> hit = net.nearestSegment(x, z, 0.5);
        if (hit.isPresent()) {
            RoadSegment s = hit.get().segment();
            double t = hit.get().t();
            return new Snap(s.curve().x(t), s.curve().z(t), s.heightAt(t), SnapKind.ROAD);
        }
        if (free) return new Snap(x, z, null, SnapKind.FREE);
        Snap from = mode == Mode.CURVE && control != null ? null : start;
        if (from != null) {
            double dx = x - from.x(), dz = z - from.z();
            double angle = Math.toDegrees(Math.atan2(dz, dx));
            double snapped = Math.round(angle / ANGLE_STEP) * ANGLE_STEP;
            if (Math.abs(angle - snapped) <= ANGLE_SNAP) {
                double len = Math.max(1, Math.round(Math.sqrt(dx * dx + dz * dz)));
                double r = Math.toRadians(snapped);
                return new Snap(from.x() + Math.cos(r) * len, from.z() + Math.sin(r) * len, null, SnapKind.ANGLE);
            }
        } else {
            // Grid lines run down the middle of zone cells, so a road fills one cell row and leaves the rows on
            // either side free to zone.
            double gx = Math.round((x - 4.5) / GRID) * GRID + 4.5, gz = Math.round((z - 4.5) / GRID) * GRID + 4.5;
            return new Snap(gx, gz, null, SnapKind.GRID);
        }
        return new Snap(Math.floor(x) + 0.5, Math.floor(z) + 0.5, null, SnapKind.FREE);
    }

    // ---------------------------------------------------------------- clicks

    /** Left click. Returns the curve to build when this click finishes a road, else null. */
    public @Nullable RoadCurve click() {
        if (cursor == null) return null;
        if (start == null) { start = cursor; return null; }
        if (mode == Mode.CURVE && control == null) {
            if (dist(start, cursor) < 2) return null;
            control = cursor;
            return null;
        }
        RoadCurve curve = currentCurve();
        if (curve == null || preview == null || !preview.buildable()) return null;
        // Chain: the next road starts where this one ends, like Cities: Skylines.
        start = new Snap(curve.bx(), curve.bz(), null, SnapKind.FREE);
        control = null;
        return curve;
    }

    public @Nullable RoadCurve currentCurve() {
        if (start == null || cursor == null || dist(start, cursor) < 0.5) return null;
        if (mode == Mode.CURVE && control != null) {
            return new RoadCurve(start.x(), start.z(), control.x(), control.z(), cursor.x(), cursor.z());
        }
        return RoadCurve.straight(start.x(), start.z(), cursor.x(), cursor.z());
    }

    private static double dist(Snap a, Snap b) {
        return Math.hypot(a.x() - b.x(), a.z() - b.z());
    }

    // ---------------------------------------------------------------- preview

    /** The plan for the road under the cursor, recomputed only when something it depends on changes. */
    public @Nullable Preview preview(@Nullable CitySummary city) {
        RoadCurve curve = currentCurve();
        Level level = Minecraft.getInstance().level;
        if (curve == null || city == null || level == null) { preview = null; previewKey = null; return null; }
        Object key = List.of(curve, type, ClientRoads.version(), city.stock().hashCode(), Objects.hash(start, cursor));
        if (key.equals(previewKey)) return preview;
        previewKey = key;

        RoadNetwork planned = ClientRoads.network().copy();
        Integer startY = pinned(planned, start);
        Integer endY = pinned(planned, cursor);
        RoadGrading.Result graded = RoadGrading.grade(level, type, curve, startY, endY);
        int[] h = graded.heights();
        int a = nodeAt(planned, curve.ax(), curve.az(), h[0]);
        int b = nodeAt(planned, curve.bx(), curve.bz(), h[h.length - 1]);
        RoadSegment piece = planned.addSegment(new RoadSegment(0, city.id(), type, a, b, curve, h, 0, List.of()));
        RoadPlan plan = RoadPlan.of(level, planned, List.of(piece),
                k -> city.contains(RoadRaster.keyX(k), RoadRaster.keyZ(k)));
        double len = curve.length();
        if (len < RoadPlan.MIN_LENGTH) plan.issues.add(RoadPlan.Issue.TOO_SHORT);
        if (graded.tooSteep()) plan.issues.add(RoadPlan.Issue.TOO_STEEP);
        if (graded.unloaded()) plan.issues.add(RoadPlan.Issue.NOT_LOADED);
        boolean free = Minecraft.getInstance().player != null && Minecraft.getInstance().player.getAbilities().instabuild;
        Map<Material, Integer> missing = free ? Map.of() : Stock.missing(city.stock(), plan.cost);
        preview = new Preview(curve, piece, plan, missing);
        return preview;
    }

    private static @Nullable Integer pinned(RoadNetwork net, @Nullable Snap snap) {
        if (snap == null) return null;
        if (snap.y() != null) return snap.y();
        return net.nearestNode(snap.x(), snap.z(), 0.25).map(RoadNode::y).orElse(null);
    }

    private static int nodeAt(RoadNetwork net, double x, double z, int y) {
        return net.nearestNode(x, z, RoadEditor.NODE_SNAP).map(RoadNode::id).orElseGet(() -> net.addNode(x, y, z).id());
    }
}
