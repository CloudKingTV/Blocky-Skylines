package com.cloudkingtv.townstead.road;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.world.phys.Vec2;

/** Turns road centrelines into block columns. Pure geometry: the same on client (preview) and server (build). */
public final class RoadRaster {
    /** One road column. {@code owner} is a segment id, or {@code -nodeId - 1} for a junction cap. */
    public record Cell(int x, int z, int y, RoadType.Part part, double dist, int owner) {
        /** Junctions: a carriageway beats a median beats a sidewalk; then the nearer centreline; then the older road. */
        public boolean beats(Cell other) {
            if (part.rank != other.part.rank) return part.rank > other.part.rank;
            if (Math.abs(dist - other.dist) > 1e-6) return dist < other.dist;
            return owner < other.owner;
        }
    }

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int keyX(long key) { return (int) (key >> 32); }
    public static int keyZ(long key) { return (int) key; }

    /** Columns of one road piece, each with the cross-section part nearest its centreline. */
    public static Map<Long, Cell> segment(int owner, RoadType type, RoadCurve curve, int[] heights) {
        Map<Long, Cell> out = new HashMap<>();
        double hw = type.halfWidth();
        int steps = Math.max(4, (int) Math.ceil(curve.length() * 4));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double px = curve.x(t), pz = curve.z(t);
            Vec2 d = curve.direction(t);
            double nx = -d.y, nz = d.x; // left-hand normal
            int y = RoadSegment.heightAt(heights, t);
            for (double u = -hw + 0.125; u < hw; u += 0.25) {
                int column = Math.min(type.width() - 1, Math.max(0, (int) Math.floor(u + hw)));
                int x = (int) Math.floor(px + nx * u), z = (int) Math.floor(pz + nz * u);
                Cell cell = new Cell(x, z, y, type.part(column), Math.abs(u), owner);
                out.merge(key(x, z), cell, (old, neu) -> neu.dist < old.dist ? neu : old);
            }
        }
        return out;
    }

    /**
     * A round paved cap at a junction, so corners where roads meet at an angle are filled in. The radius is the
     * widest carriageway meeting there, ringed by a sidewalk when any of those roads has one.
     */
    public static Map<Long, Cell> junctionCap(RoadNode node, double carriageHalf, boolean sidewalk) {
        Map<Long, Cell> out = new HashMap<>();
        double outer = carriageHalf + (sidewalk ? 1 : 0);
        int r = (int) Math.ceil(outer) + 1;
        int cx = (int) Math.floor(node.x()), cz = (int) Math.floor(node.z());
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                double d = Math.sqrt(Math.pow(x + 0.5 - node.x(), 2) + Math.pow(z + 0.5 - node.z(), 2));
                if (d > outer) continue;
                RoadType.Part part = d <= carriageHalf ? RoadType.Part.CARRIAGEWAY : RoadType.Part.SIDEWALK;
                // A cap never beats a real road's own column of the same rank (its dist is pushed out).
                out.put(key(x, z), new Cell(x, z, node.y(), part, d + 0.01, -node.id() - 1));
            }
        }
        return out;
    }

    /** Half-width of the paved (non-sidewalk) part of a road type. */
    public static double carriageHalf(RoadType type) {
        int first = 0, last = type.width() - 1;
        while (first < last && type.part(first) == RoadType.Part.SIDEWALK) first++;
        while (last > first && type.part(last) == RoadType.Part.SIDEWALK) last--;
        return (last - first + 1) / 2.0;
    }

    public static boolean hasSidewalk(RoadType type) {
        return type.part(0) == RoadType.Part.SIDEWALK;
    }

    private RoadRaster() {}
}
