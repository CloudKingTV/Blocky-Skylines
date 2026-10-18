package com.cloudkingtv.townstead.road;

import java.util.Arrays;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec2;
import org.jspecify.annotations.Nullable;

/**
 * Fits a road's surface height to the terrain: follow the ground (ignoring tree trunks and lamp posts), smooth
 * it, and cap the grade at one block up per {@link #RUN_PER_RISE} blocks along. Ends that join existing roads are
 * pinned to those roads' heights.
 */
public final class RoadGrading {
    /** Blocks of road per block of climb (2 = a 50% grade, the steepest a Minecraft path feels right at). */
    public static final double RUN_PER_RISE = 2.0;

    public record Result(int[] heights, boolean tooSteep, boolean unloaded) {}

    public static Result grade(LevelReader level, RoadType type, RoadCurve curve, @Nullable Integer startY, @Nullable Integer endY) {
        double length = curve.length();
        int n = Math.max(2, (int) Math.ceil(length) + 1);
        double[] g = new double[n];
        boolean unloaded = false;
        double hw = type.halfWidth();
        for (int i = 0; i < n; i++) {
            double t = (double) i / (n - 1);
            double px = curve.x(t), pz = curve.z(t);
            Vec2 d = curve.direction(t);
            int samples = Math.max(3, type.width());
            int[] ys = new int[samples];
            for (int k = 0; k < samples; k++) {
                double u = -hw + (k + 0.5) * (2 * hw / samples);
                int x = Mth.floor(px - d.y * u), z = Mth.floor(pz + d.x * u);
                if (!level.hasChunk(x >> 4, z >> 4)) unloaded = true;
                ys[k] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            }
            Arrays.sort(ys);
            g[i] = ys[samples / 2]; // median: a trunk or post under one edge doesn't lift the road
        }
        if (startY != null) g[0] = startY;
        if (endY != null) g[n - 1] = endY;

        // Smooth bumps (pins stay put).
        for (int pass = 0; pass < 3; pass++) {
            double[] s = g.clone();
            for (int i = 1; i < n - 1; i++) {
                double sum = 0;
                int count = 0;
                for (int j = Math.max(0, i - 3); j <= Math.min(n - 1, i + 3); j++) { sum += g[j]; count++; }
                s[i] = sum / count;
            }
            if (startY == null) s[0] = (g[0] + g[1]) / 2;
            if (endY == null) s[n - 1] = (g[n - 1] + g[n - 2]) / 2;
            g = s;
        }

        // Grade limit, forwards from the start then backwards from the end.
        double step = length / (n - 1) / RUN_PER_RISE;
        for (int i = 1; i < n; i++) g[i] = Mth.clamp(g[i], g[i - 1] - step, g[i - 1] + step);
        if (endY != null) g[n - 1] = endY;
        for (int i = n - 2; i >= 0; i--) g[i] = Mth.clamp(g[i], g[i + 1] - step, g[i + 1] + step);
        boolean tooSteep = startY != null && Math.abs(g[0] - startY) > 0.01;

        int[] heights = new int[n];
        for (int i = 0; i < n; i++) heights[i] = (int) Math.round(g[i]);
        if (startY != null) heights[0] = startY;
        if (endY != null) heights[n - 1] = endY;
        return new Result(heights, tooSteep, unloaded);
    }

    private RoadGrading() {}
}
