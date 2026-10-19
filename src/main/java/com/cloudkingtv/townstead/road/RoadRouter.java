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

    public RoadRouter(RoadNetwork net, UUID city) {
        this.net = net;
        this.city = city;
        for (RoadSegment s : net.segments()) {
            if (!s.city().equals(city)) continue;
            adjacency.computeIfAbsent(s.a(), k -> new ArrayList<>()).add(s);
            adjacency.computeIfAbsent(s.b(), k -> new ArrayList<>()).add(s);
            lengths.put(s.id(), Math.max(1, s.curve().length()));
        }
    }

    public RoadNetwork network() { return net; }

    /** The town's segments. */
    public java.util.Collection<Integer> segmentIds() { return lengths.keySet(); }

    public double length(int segment) { return lengths.getOrDefault(segment, 1.0); }

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
        for (int id : lengths.keySet()) {
            RoadSegment s = net.segment(id);
            if (s == null) continue;
            double reach = s.type().halfWidth() + slack;
            double t = s.curve().closestT(x, z);
            double d = s.curve().distance(t, x, z);
            if (d <= reach && d < bestD) { bestD = d; best = new Access(id, t); }
        }
        return best;
    }

    /**
     * The quickest way from one place to another, as legs, or null if they aren't connected.
     *
     * @param time how long it takes to drive a whole segment (free-flow time, or with congestion)
     */
    public @Nullable List<Leg> route(Access from, Access to, ToDoubleFunction<RoadSegment> time) {
        if (from.segment() == to.segment()) return List.of(new Leg(from.segment(), from.t(), to.t()));
        RoadSegment start = net.segment(from.segment()), end = net.segment(to.segment());
        if (start == null || end == null) return null;
        Map<Integer, Double> dist = new HashMap<>();
        Map<Integer, Integer> viaSegment = new HashMap<>(), viaNode = new HashMap<>();
        PriorityQueue<double[]> queue = new PriorityQueue<>((p, q) -> Double.compare(p[1], q[1]));
        double startTime = time.applyAsDouble(start);
        // Leave the first segment by either end.
        seed(dist, queue, start.a(), startTime * from.t());
        seed(dist, queue, start.b(), startTime * (1 - from.t()));
        double endTime = time.applyAsDouble(end);
        while (!queue.isEmpty()) {
            double[] top = queue.poll();
            int node = (int) top[0];
            if (top[1] > dist.getOrDefault(node, Double.MAX_VALUE)) continue;
            for (RoadSegment s : adjacency.getOrDefault(node, List.of())) {
                if (s.id() == start.id()) continue;
                int other = s.a() == node ? s.b() : s.a();
                double d = top[1] + time.applyAsDouble(s);
                if (d < dist.getOrDefault(other, Double.MAX_VALUE)) {
                    dist.put(other, d);
                    viaSegment.put(other, s.id());
                    viaNode.put(other, node);
                    queue.add(new double[] {other, d});
                }
            }
        }
        // Enter the last segment by whichever end is quicker overall.
        double viaA = dist.getOrDefault(end.a(), Double.MAX_VALUE) + endTime * to.t();
        double viaB = dist.getOrDefault(end.b(), Double.MAX_VALUE) + endTime * (1 - to.t());
        if (viaA == Double.MAX_VALUE && viaB == Double.MAX_VALUE) return null;
        int entry = viaA <= viaB ? end.a() : end.b();
        List<Leg> legs = new ArrayList<>();
        legs.add(new Leg(end.id(), entry == end.a() ? 0 : 1, to.t()));
        int node = entry;
        while (viaSegment.containsKey(node)) {
            int segId = viaSegment.get(node), prev = viaNode.get(node);
            RoadSegment s = net.segment(segId);
            legs.add(new Leg(segId, s.a() == prev ? 0 : 1, s.a() == prev ? 1 : 0));
            node = prev;
        }
        legs.add(new Leg(start.id(), from.t(), node == start.a() ? 0 : 1));
        Collections.reverse(legs);
        return legs;
    }

    private static void seed(Map<Integer, Double> dist, PriorityQueue<double[]> queue, int node, double d) {
        if (d < dist.getOrDefault(node, Double.MAX_VALUE)) {
            dist.put(node, d);
            queue.add(new double[] {node, d});
        }
    }

    /** Length in blocks of a route. */
    public double length(List<Leg> legs) {
        double n = 0;
        for (Leg l : legs) n += Math.abs(l.t1() - l.t0()) * length(l.segment());
        return n;
    }
}
