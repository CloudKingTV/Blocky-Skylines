package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.city.TilePos;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/** Draws the town's tile borders and the zone-cell cursor on top of the terrain while planning. */
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
        });
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
