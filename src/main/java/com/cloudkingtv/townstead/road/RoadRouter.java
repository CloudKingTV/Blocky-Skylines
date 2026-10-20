package com.cloudkingtv.townstead.road;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import org.jspecify.annotations.Nullable;

/**
 * Finds ways through one town's roads: from a point on one segment to a point on another, over the junction graph.
 * Used for traffic (who drives which road), coach lines (the route between stops) and the carts and coaches drawn on
 * the streets. Pure, so server and client route the same way.
 */
public final class RoadRouter {
    /** A place on the road: a segment and the curve parameter along it. */
    public record Access(int segment, double t) {}

    /** Driving along one segment from parameter {@code t0} to {@code t1} (either direction). */
    public record Leg(int segment, double t0, double t1) {}

    private final RoadNetwork net;
    private final UUID city;
    /** Node to the segments that end there. */
    private final Map<Integer, List<RoadSegment>> adjacency = new HashMap<>();
    private final Map<Integer, Double> lengths = new HashMap<>();
    /** Junctions by dense index, for the path search. */
    private final Map<Integer, Integer> index = new HashMap<>();
    private final int[] nodeIds;
    private final RoadSegment[][] adjacent;
    /** For each junction's segments, the junction at the other end. */
    private final int[][] neighbour;
    /** The town's segments by dense index, and each junction's segments as such indices. */
    private final RoadSegment[] segs;
    private final Map<Integer, Integer> segIndex = new HashMap<>();
    private final int[][] adjacentIndex;

    public RoadRouter(RoadNetwork net, UUID city) {
        this.net = net;
        this.city = city;
        for (RoadSegment s : net.segments()) {
            if (!s.city().equals(city)) continue;
            adjacency.computeIfAbsent(s.a(), k -> new ArrayList<>()).add(s);
            adjacency.computeIfAbsent(s.b(), k -> new ArrayList<>()).add(s);
            lengths.put(s.id(), Math.max(1, s.curve().length()));
        }
        nodeIds = adjacency.keySet().stream().mapToInt(Integer::intValue).toArray();
        adjacent = new RoadSegment[nodeIds.length][];
        neighbour = new int[nodeIds.length][];
        for (int i = 0; i < nodeIds.length; i++) {
            index.put(nodeIds[i], i);
            adjacent[i] = adjacency.get(nodeIds[i]).toArray(RoadSegment[]::new);
        }
        segs = new RoadSegment[lengths.size()];
        for (RoadSegment s : net.segments()) {
            if (!lengths.containsKey(s.id())) continue;
            segIndex.put(s.id(), segIndex.size());
            segs[segIndex.get(s.id())] = s;
        }
        adjacentIndex = new int[nodeIds.length][];
        for (int i = 0; i < nodeIds.length; i++) {
            adjacentIndex[i] = new int[adjacent[i].length];
            for (int k = 0; k < adjacent[i].length; k++) adjacentIndex[i][k] = segIndex.get(adjacent[i][k].id());
        }
        for (int i = 0; i < nodeIds.length; i++) {
            neighbour[i] = new int[adjacent[i].length];
            for (int k = 0; k < adjacent[i].length; k++) {
                RoadSegment s = adjacent[i][k];
                neighbour[i][k] = index.get(s.a() == nodeIds[i] ? s.b() : s.a());
            }
        }
    }

    public RoadNetwork network() { return net; }

    /** The town's segments. */
    public java.util.Collection<Integer> segmentIds() { return lengths.keySet(); }

    public double length(int segment) { return lengths.getOrDefault(segment, 1.0); }

    /** How many town segments there are, for arrays indexed by {@link #index}. */
    public int segmentCount() { return segs.length; }

    /** A town segment's dense index (0 to {@link #segmentCount()} - 1), or -1 if it isn't one. */
    public int index(int segment) { return segIndex.getOrDefault(segment, -1); }

    public RoadSegment segmentAt(int index) { return segs[index]; }

    /** Every town segment's time, by dense index. */
    public double[] times(ToDoubleFunction<RoadSegment> time) {
        double[] out = new double[segs.length];
        for (int i = 0; i < segs.length; i++) out[i] = time.applyAsDouble(segs[i]);
        return out;
    }

    /** Blocks per tick-ish unit: wider roads are quicker. */
    public static double speed(RoadType type) {
        return switch (type) {
            case LANE -> 1.0;
            case STREET -> 1.3;
            case AVENUE -> 1.8;
        };
    }

    /** Time to drive a whole segment with the road empty. */
    public double freeTime(RoadSegment s) {
        return length(s.id()) / speed(s.type());
    }

    /** The town road nearest (x, z), within {@code slack} blocks of its edge. */
    public @Nullable Access access(double x, double z, double slack) {
        Access best = null;
        double bestD = Double.MAX_VALUE;
        java.util.Collection<Integer> candidates = slack <= BUCKET_SLACK ? nearby(x, z) : lengths.keySet();
        for (int id : candidates) {
            RoadSegment s = net.segment(id);
            if (s == null) continue;
            double reach = s.type().halfWidth() + slack;
            double t = s.curve().closestT(x, z);
            double d = s.curve().distance(t, x, z);
            if (d <= reach && d < bestD) { bestD = d; best = new Access(id, t); }
        }
        return best;
    }

    /** Segments are filed under the 16-block squares their (widened) bounds touch, for quick nearest-road lookups. */
    private static final int BUCKET = 16;
    private static final double BUCKET_SLACK = 8;
    private @Nullable Map<Long, List<Integer>> buckets;

    private java.util.Collection<Integer> nearby(double x, double z) {
        if (buckets == null) {
            buckets = new HashMap<>();
            for (int id : lengths.keySet()) {
                RoadSegment s = net.segment(id);
                if (s == null) continue;
                RoadCurve c = s.curve();
                double pad = s.type().halfWidth() + BUCKET_SLACK + 1;
                int x0 = (int) Math.floor((Math.min(c.ax(), Math.min(c.cx(), c.bx())) - pad) / BUCKET);
                int x1 = (int) Math.floor((Math.max(c.ax(), Math.max(c.cx(), c.bx())) + pad) / BUCKET);
                int z0 = (int) Math.floor((Math.min(c.az(), Math.min(c.cz(), c.bz())) - pad) / BUCKET);
                int z1 = (int) Math.floor((Math.max(c.az(), Math.max(c.cz(), c.bz())) + pad) / BUCKET);
                for (int bx = x0; bx <= x1; bx++) for (int bz = z0; bz <= z1; bz++) {
                    buckets.computeIfAbsent(((long) bx << 32) | (bz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(id);
                }
            }
        }
        int bx = (int) Math.floor(x / BUCKET), bz = (int) Math.floor(z / BUCKET);
        return buckets.getOrDefault(((long) bx << 32) | (bz & 0xFFFFFFFFL), List.of());
    }

    /**
     * The quickest way from one place to another, as legs, or null if they aren't connected.
     *
     * @param time how long it takes to drive a whole segment (free-flow time, or with congestion)
     */
    public @Nullable List<Leg> route(Access from, Access to, ToDoubleFunction<RoadSegment> time) {
        if (from.segment() == to.segment()) return List.of(new Leg(from.segment(), from.t(), to.t()));
        double[] times = times(time);
        Tree tree = tree(from, times);
        return tree == null ? null : route(tree, to, times);
    }

    /**
     * Quickest times from one place to every junction of the town (one Dijkstra), so many trips from the same place
     * can be routed at the cost of one.
     */
    public static final class Tree {
        final Access from;
        final RoadSegment start;
        final double[] dist;
        final int[] viaSegment, viaNode;

        Tree(Access from, RoadSegment start, int n) {
            this.from = from;
            this.start = start;
            dist = new double[n];
            viaSegment = new int[n];
            viaNode = new int[n];
            java.util.Arrays.fill(dist, Double.MAX_VALUE);
            java.util.Arrays.fill(viaSegment, -1);
        }
    }

    /** The shortest-path tree from a place, or null if its road isn't the town's. */
    public @Nullable Tree tree(Access from, double[] times) {
        RoadSegment start = net.segment(from.segment());
        if (start == null || !index.containsKey(start.a()) || !index.containsKey(start.b())) return null;
        Tree tree = new Tree(from, start, nodeIds.length);
        PriorityQueue<double[]> queue = new PriorityQueue<>((p, q) -> Double.compare(p[1], q[1]));
        double startTime = times[segIndex.get(start.id())];
        // Leave the first segment by either end.
        seed(tree, queue, index.get(start.a()), startTime * from.t());
        seed(tree, queue, index.get(start.b()), startTime * (1 - from.t()));
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int node = (int) top[0];
            if (top[1] > tree.dist[node]) continue;
            RoadSegment[] around = adjacent[node];
            for (int k = 0; k < around.length; k++) {
                RoadSegment s = around[k];
                if (s.id() == start.id()) continue;
                int other = neighbour[node][k];
                double d = top[1] + times[adjacentIndex[node][k]];
                if (d < tree.dist[other]) {
                    tree.dist[other] = d;
                    tree.viaSegment[other] = s.id();
                    tree.viaNode[other] = node;
                    queue.add(new double[] {other, d});
                }
            }
        }
        return tree;
    }

    /** The quickest way along a tree to {@code to}, or null if it can't be reached. */
    public @Nullable List<Leg> route(Tree tree, Access to, double[] times) {
        Access from = tree.from;
        if (from.segment() == to.segment()) return List.of(new Leg(from.segment(), from.t(), to.t()));
        RoadSegment start = tree.start, end = net.segment(to.segment());
        if (end == null) return null;
        Integer ea = index.get(end.a()), eb = index.get(end.b());
        if (ea == null || eb == null) return null;
        double endTime = times[segIndex.get(end.id())];
        // Enter the last segment by whichever end is quicker overall.
        double viaA = tree.dist[ea] == Double.MAX_VALUE ? Double.MAX_VALUE : tree.dist[ea] + endTime * to.t();
        double viaB = tree.dist[eb] == Double.MAX_VALUE ? Double.MAX_VALUE : tree.dist[eb] + endTime * (1 - to.t());
        if (viaA == Double.MAX_VALUE && viaB == Double.MAX_VALUE) return null;
        int entry = viaA <= viaB ? ea : eb;
        List<Leg> legs = new ArrayList<>();
        legs.add(new Leg(end.id(), entry == ea ? 0 : 1, to.t()));
        int node = entry;
        while (tree.viaSegment[node] >= 0) {
            int segId = tree.viaSegment[node], prev = tree.viaNode[node];
            RoadSegment s = net.segment(segId);
            legs.add(new Leg(segId, index.get(s.a()) == prev ? 0 : 1, index.get(s.a()) == prev ? 1 : 0));
            node = prev;
        }
        legs.add(new Leg(start.id(), from.t(), nodeIds[node] == start.a() ? 0 : 1));
        Collections.reverse(legs);
        return legs;
    }

    private static void seed(Tree tree, PriorityQueue<double[]> queue, int node, double d) {
        if (d < tree.dist[node]) {
            tree.dist[node] = d;
            queue.add(new double[] {node, d});
        }
    }

    /**
     * Where a coach stop meets the road now: its own segment and place if that still runs past the post, otherwise
     * (the road was split by a junction, or rebuilt) the town road nearest the post.
     */
    public @Nullable Access stop(int segment, double t, net.minecraft.core.BlockPos marker) {
        RoadSegment s = net.segment(segment);
        double mx = marker.getX() + 0.5, mz = marker.getZ() + 0.5;
        if (s != null && lengths.containsKey(segment) && Math.hypot(s.curve().x(t) - mx, s.curve().z(t) - mz) <= s.type().halfWidth() + 3.5) {
            return new Access(segment, t);
        }
        return access(mx, mz, 4);
    }

    /** Length in blocks of a route. */
    public double length(List<Leg> legs) {
        double n = 0;
        for (Leg l : legs) n += Math.abs(l.t1() - l.t0()) * length(l.segment());
        return n;
    }
}
