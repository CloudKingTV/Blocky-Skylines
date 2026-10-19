package com.cloudkingtv.townstead.zone;

import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.city.TilePos;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadRaster;
import com.cloudkingtv.townstead.road.RoadSegment;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * Which 8x8 cells can be zoned: on town land, clear of road blocks, with a road running along one edge (the
 * frontage). The frontage side is where buildings put their door. Pure geometry, shared by client and server.
 */
public final class ZoneRules {
    public static final int CELL = TilePos.CELL;

    public static long cellKey(int cellX, int cellZ) {
        return RoadRaster.key(cellX, cellZ);
    }

    /** Road columns of a town's roads (any part), keyed like {@link RoadRaster#key}. */
    public static Map<Long, RoadRaster.Cell> roadColumns(RoadNetwork net, UUID city) {
        Map<Long, RoadRaster.Cell> out = new HashMap<>();
        for (RoadSegment s : net.segments()) {
            if (s.city().equals(city)) out.putAll(net.raster(s));
        }
        return out;
    }

    /** How far outside a cell edge a road may run and still count as the lot's frontage (a grass verge). */
    public static final int REACH = 4;
    /** Rows along the frontage a road may take up inside a lot. */
    public static final int MAX_SETBACK = 2;

    /** How many rows along the front edge the road occupies inside the cell (0 when it runs outside). */
    public static int setback(int cellX, int cellZ, Direction front, Map<Long, RoadRaster.Cell> roads) {
        int rows = 0;
        for (int x = 0; x < CELL; x++) {
            for (int z = 0; z < CELL; z++) {
                if (roads.containsKey(RoadRaster.key(cellX * CELL + x, cellZ * CELL + z))) rows = Math.max(rows, depth(front, x, z) + 1);
            }
        }
        return rows;
    }

    /**
     * The side of the cell facing its road, or null if the cell can't be zoned. A road counts when it runs along an
     * edge within {@link #REACH} blocks outside the cell, or through the cell's first {@link #MAX_SETBACK} rows on
     * that edge (the building then sits further back, see {@link #setback}). Road deeper inside rules the cell out.
     */
    public static @Nullable Direction frontage(int cellX, int cellZ, Map<Long, RoadRaster.Cell> roads, Set<TilePos> tiles) {
        int x0 = cellX * CELL, z0 = cellZ * CELL;
        if (!tiles.contains(TilePos.ofBlock(x0, z0))) return null;
        java.util.List<int[]> inside = new java.util.ArrayList<>();
        for (int x = x0; x < x0 + CELL; x++) {
            for (int z = z0; z < z0 + CELL; z++) if (roads.containsKey(RoadRaster.key(x, z))) inside.add(new int[] {x - x0, z - z0});
        }
        Direction best = null;
        int bestCount = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            boolean ok = true;
            for (int[] c : inside) if (depth(d, c[0], c[1]) >= MAX_SETBACK) { ok = false; break; }
            if (!ok) continue;
            int count = 0;
            for (int i = 0; i < CELL; i++) {
                boolean road = false;
                for (int k = 0; k <= REACH && !road; k++) { // k = 0 is the cell's own edge row
                    int[] p = along(d, i, k);
                    road = roads.containsKey(RoadRaster.key(x0 + p[0], z0 + p[1]));
                }
                if (road) count++;
            }
            if (count > bestCount) { bestCount = count; best = d; }
        }
        return bestCount >= 3 ? best : null; // a road has to run along the edge, not just touch a corner
    }

    /** Rows from the edge facing d (0 = the edge row itself) of cell-local (lx, lz). */
    static int depth(Direction d, int lx, int lz) {
        return switch (d) {
            case NORTH -> lz;
            case SOUTH -> CELL - 1 - lz;
            case WEST -> lx;
            default -> CELL - 1 - lx;
        };
    }

    /** Cell-local position i along the edge facing d, k blocks out from the edge row (k = 0 is the edge row). */
    public static int[] along(Direction d, int i, int k) {
        return switch (d) {
            case NORTH -> new int[] {i, -k};
            case SOUTH -> new int[] {i, CELL - 1 + k};
            case WEST -> new int[] {-k, i};
            default -> new int[] {CELL - 1 + k, i};
        };
    }

    /**
     * Whether a cell can be zoned: it faces a road ({@link #frontage}), or it lies right behind a cell that does
     * (the back half of a deep lot). Returns the direction the lot faces, or null.
     */
    public static @Nullable Direction zonable(int cellX, int cellZ, Map<Long, RoadRaster.Cell> roads, Set<TilePos> tiles) {
        Direction f = frontage(cellX, cellZ, roads, tiles);
        if (f != null) return f;
        if (!tiles.contains(TilePos.ofBlock(cellX * CELL, cellZ * CELL)) || hasRoad(cellX, cellZ, roads)) return null;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (frontage(cellX + d.getStepX(), cellZ + d.getStepZ(), roads, tiles) == d) return d;
        }
        return null;
    }

    /** Whether any road column crosses the cell. */
    public static boolean hasRoad(int cellX, int cellZ, Map<Long, RoadRaster.Cell> roads) {
        for (int x = cellX * CELL; x < cellX * CELL + CELL; x++) {
            for (int z = cellZ * CELL; z < cellZ * CELL + CELL; z++) if (roads.containsKey(RoadRaster.key(x, z))) return true;
        }
        return false;
    }

    /**
     * Whether a lot anchored at (cellX, cellZ) facing {@code front} fits: its front row of cells all face the road the
     * same way, its back row has no road through it, and everything is town land. {@code free} says whether a cell
     * may be used (unbuilt, zoned right...).
     */
    public static boolean lotFits(int cellX, int cellZ, Direction front, int wide, int deep, Map<Long, RoadRaster.Cell> roads,
                                  Set<TilePos> tiles, java.util.function.LongPredicate free) {
        Direction right = front.getCounterClockWise(), back = front.getOpposite();
        for (int d = 0; d < deep; d++) {
            for (int a = 0; a < wide; a++) {
                int x = cellX + right.getStepX() * a + back.getStepX() * d, z = cellZ + right.getStepZ() * a + back.getStepZ() * d;
                if (!free.test(cellKey(x, z)) || !tiles.contains(TilePos.ofBlock(x * CELL, z * CELL))) return false;
                if (d == 0 ? frontage(x, z, roads, tiles) != front : hasRoad(x, z, roads)) return false;
            }
        }
        return true;
    }

    public static @Nullable Direction frontage(int cellX, int cellZ, RoadNetwork net, CitySummary city) {
        return frontage(cellX, cellZ, roadColumns(net, city.id()), Set.copyOf(city.tiles()));
    }


    private ZoneRules() {}
}
