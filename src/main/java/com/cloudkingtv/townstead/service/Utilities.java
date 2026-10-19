package com.cloudkingtv.townstead.service;

import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadRaster;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.zone.ZoneRules;
import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Power and water run along the roads (as in Cities: Skylines 2): every building on a connected road network
 * shares the plants and wells on that network. When a network asks for more than it has, the oldest buildings are
 * served first and the rest go without (abandoned ones last). Services cover everything within their radius. Pure functions of the town,
 * so the server (levels, trouble) and the client (icons, info views, the panel) agree.
 */
public final class Utilities {
    /** Supply on one road network: capacity and what's asked of it. */
    public record Grid(int powerCapacity, int powerUsed, int waterCapacity, int waterUsed) {}

    /** What reaches each building, and per-network totals. */
    public record State(Set<Integer> powered, Set<Integer> watered, Map<Integer, Grid> grids, Map<Integer, Integer> networkOf) {
        public boolean power(Building b) { return powered.contains(b.id); }
        public boolean water(Building b) { return watered.contains(b.id); }
        public @Nullable Grid grid(Building b) { Integer n = networkOf.get(b.id); return n == null ? null : grids.get(n); }
    }

    /** Units a building uses of power and of water. */
    public static int demand(Building b) {
        if (b.civic()) return b.serviceType().kind == ServiceType.Kind.SERVICE ? 1 : 0;
        return switch (b.zone.need) {
            case HOMES -> Math.max(1, b.households);
            case SHOPS, OFFICES -> Math.max(1, b.jobs / 2);
            case WORKSHOPS -> Math.max(1, b.jobs);
            case CIVIC -> 0;
        };
    }

    public static State compute(Collection<Building> town, RoadNetwork roads) {
        return compute(town, roads, s -> 1.0);
    }

    /** {@code strength}: how hard each plant works (its department budget: 1.0 at 100%). */
    public static State compute(Collection<Building> town, RoadNetwork roads, java.util.function.ToDoubleFunction<ServiceType> strength) {
        // Road networks: union-find over road nodes.
        Map<Integer, Integer> parent = new HashMap<>();
        for (RoadSegment s : roads.segments()) union(parent, s.a(), s.b());
        Map<Integer, Integer> networkOf = new HashMap<>();
        Map<Long, RoadRaster.Cell> cols = new HashMap<>();
        Set<java.util.UUID> cities = new HashSet<>();
        for (Building b : town) cities.add(b.city);
        for (java.util.UUID c : cities) cols.putAll(ZoneRules.roadColumns(roads, c));
        for (Building b : town) {
            Integer node = frontNode(b, roads, cols);
            if (node != null) networkOf.put(b.id, find(parent, node));
        }
        Map<Integer, int[]> cap = new HashMap<>(); // network -> {power, water}
        for (Building b : town) {
            if (!b.civic() || b.state != Building.State.ACTIVE || !networkOf.containsKey(b.id)) continue;
            ServiceType s = b.serviceType();
            int[] c = cap.computeIfAbsent(networkOf.get(b.id), k -> new int[2]);
            int capacity = (int) Math.round(s.capacity * strength.applyAsDouble(s));
            if (s.kind == ServiceType.Kind.POWER) c[0] += capacity;
            if (s.kind == ServiceType.Kind.WATER) c[1] += capacity;
        }
        List<Building> users = new ArrayList<>(town);
        // Oldest first; abandoned buildings last, so supply for their return is checked without crowding out others.
        users.sort(Comparator.comparing((Building b) -> b.state == Building.State.ABANDONED).thenComparingInt(b -> b.id));
        Map<Integer, int[]> used = new HashMap<>();
        Set<Integer> powered = new HashSet<>(), watered = new HashSet<>();
        for (Building b : users) {
            Integer net = networkOf.get(b.id);
            if (net == null) continue;
            int need = demand(b);
            if (need == 0) continue;
            int[] c = cap.getOrDefault(net, new int[2]);
            int[] u = used.computeIfAbsent(net, k -> new int[2]);
            if (u[0] + need <= c[0]) { u[0] += need; powered.add(b.id); }
            if (u[1] + need <= c[1]) { u[1] += need; watered.add(b.id); }
        }
        Map<Integer, Grid> grids = new HashMap<>();
        Set<Integer> nets = new HashSet<>(networkOf.values());
        for (int n : nets) {
            int[] c = cap.getOrDefault(n, new int[2]), u = used.getOrDefault(n, new int[2]);
            grids.put(n, new Grid(c[0], u[0], c[1], u[1]));
        }
        return new State(powered, watered, grids, networkOf);
    }

    /** Whether an active service of this type covers the building. */
    public static boolean covered(Building b, Collection<Building> town, ServiceType service) {
        return covered(b, town, service, service.radius);
    }

    /** Whether an active service of this type covers the building, with the service reaching {@code radius} blocks. */
    public static boolean covered(Building b, Collection<Building> town, ServiceType service, double radius) {
        for (Building s : town) {
            if (!s.civic() || s.state != Building.State.ACTIVE || !s.service.equals(service.key())) continue;
            double d = Math.hypot((s.cellX - b.cellX) * ZoneRules.CELL, (s.cellZ - b.cellZ) * ZoneRules.CELL);
            if (d <= radius) return true;
        }
        return false;
    }

    /** Services a growable needs for level 3, by zone. */
    public static List<ServiceType> levelThreeServices(ZoneType zone) {
        return switch (zone.need) {
            case HOMES -> List.of(ServiceType.CLINIC, ServiceType.SCHOOL, ServiceType.PARK);
            case SHOPS -> List.of(ServiceType.GUARD_HOUSE, ServiceType.COMPOST_YARD);
            case WORKSHOPS -> List.of(ServiceType.FIRE_STATION, ServiceType.COMPOST_YARD);
            case OFFICES -> List.of(ServiceType.GUARD_HOUSE, ServiceType.SCHOOL, ServiceType.CLINIC);
            case CIVIC -> List.of();
        };
    }

    /** The road node the building's frontage road reaches (any node of the segment will do for networks). */
    private static @Nullable Integer frontNode(Building b, RoadNetwork roads, Map<Long, RoadRaster.Cell> cols) {
        int x0 = b.cellX * ZoneRules.CELL, z0 = b.cellZ * ZoneRules.CELL;
        for (int k = 0; k <= ZoneRules.REACH + 1; k++) {
            for (int i = 0; i < ZoneRules.CELL; i++) {
                int[] p = ZoneRules.along(b.front, i, k);
                RoadRaster.Cell c = cols.get(RoadRaster.key(x0 + p[0], z0 + p[1]));
                if (c == null) continue;
                if (c.owner() < 0) return -c.owner() - 1; // a junction cap belongs to its node
                RoadSegment s = roads.segment(c.owner());
                if (s != null) return s.a();
            }
        }
        return null;
    }

    private static int find(Map<Integer, Integer> parent, int x) {
        int p = parent.getOrDefault(x, x);
        if (p == x) return x;
        int root = find(parent, p);
        parent.put(x, root);
        return root;
    }

    private static void union(Map<Integer, Integer> parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent.put(ra, rb);
    }

    private Utilities() {}
}
