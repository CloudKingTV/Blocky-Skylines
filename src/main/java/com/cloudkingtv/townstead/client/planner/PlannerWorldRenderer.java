package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.city.TilePos;
import com.cloudkingtv.townstead.client.ClientRoads;
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
        });
        event.getSubmitNodeCollector().submitCustomGeometry(pose, PlannerRenderTypes.OVERLAY_QUADS, (p, buf) -> roadQuads(p, buf, cam, city, pulse));
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
