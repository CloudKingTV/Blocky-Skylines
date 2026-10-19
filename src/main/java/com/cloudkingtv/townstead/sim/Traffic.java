package com.cloudkingtv.townstead.sim;

import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.Residents;
import com.cloudkingtv.townstead.road.RoadRouter;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.road.RoadType;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.transit.CoachLine;
import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Traffic, the Cities: Skylines way but abstract: trips (commutes from homes to workplaces, errands from homes to
 * shops, deliveries from workshops to shops) are routed over the road graph a quarter at a time, each quarter
 * seeing the congestion the earlier ones made (BPR curve: time grows with (flow/capacity)^4). Commuters who live
 * and work by stops of the same coach line ride it instead of driving, and the coaches themselves take road space.
 * The result drives the traffic info view, the carts drawn on the streets, road noise and commuters' happiness.
 */
public final class Traffic {
    /** Fraction of commuters by a line who ride it at a 100% transport budget. */
    public static final double RIDER_SHARE = 0.6;
    /** How far a home or workplace may be from a stop for its people to use it. */
    public static final double STOP_REACH = 40;
    /** Road space a coach takes, in trips. */
    static final double COACH_TRIPS = 4;
    /** Line length (blocks) one coach serves at a 100% budget. */
    static final double BLOCKS_PER_COACH = 90;
    private static final int ROUNDS = 4;

    /** A running line's numbers: coaches on it, riders a day, its route round all stops. */
    public record LineStats(boolean running, int coaches, int riders, double length, List<RoadRouter.Leg> route) {}

    private record Trip(int from, int to, double weight, boolean commute) {}

    private final RoadRouter router;
    private final Map<Integer, Double> flow = new HashMap<>();
    private final Map<Integer, double[]> commute = new HashMap<>(); // home -> {free time, actual time}
    private final Map<Integer, LineStats> lines = new LinkedHashMap<>();
    private final Map<Integer, RoadRouter.Access> access = new HashMap<>();
    private int flowPercent = 100;
    private int trips, riders;

    Traffic(TownModel m, List<CoachLine> coachLines) {
        this.router = new RoadRouter(m.roads, m.city);
        Map<Integer, Building> byId = new HashMap<>();
        for (Building b : m.town) byId.put(b.id, b);
        boolean depot = m.town.stream().anyMatch(b -> b.state == Building.State.ACTIVE && ServiceType.COACH_HOUSE.key().equals(b.service));
        double strength = m.strength(ServiceType.COACH_HOUSE);
        for (CoachLine line : coachLines) {
            List<RoadRouter.Leg> route = loop(line);
            if (route == null) { lines.put(line.id(), new LineStats(false, 0, 0, 0, List.of())); continue; }
            double length = router.length(route);
            int coaches = depot ? Math.max(1, (int) Math.round(length / BLOCKS_PER_COACH * strength)) : 0;
            lines.put(line.id(), new LineStats(depot, coaches, 0, length, route));
        }

        List<Trip> all = new ArrayList<>();
        Map<Integer, Integer> lineRiders = new HashMap<>();
        for (List<Residents.Job> jobs : m.employment.values()) {
            for (Residents.Job j : jobs) {
                Building home = byId.get(j.homeId()), work = byId.get(j.workplaceId());
                if (home == null || work == null) continue;
                Integer line = ridable(home, work, coachLines, strength, j.person().first() + j.person().last() + j.homeId());
                if (line != null) { lineRiders.merge(line, 1, Integer::sum); riders++; continue; }
                all.add(new Trip(home.id, work.id, 1, true));
            }
        }
        List<Building> shops = m.town.stream().filter(b -> b.working() && b.zone.need == ZoneType.Need.SHOPS).toList();
        for (Building b : m.town) {
            if (!b.working()) continue;
            if (b.zone.residential()) {
                Building shop = nearest(b, shops);
                if (shop != null) all.add(new Trip(b.id, shop.id, 0.5 * b.households, false));
            } else if (b.zone.need == ZoneType.Need.WORKSHOPS) {
                Building shop = nearest(b, shops);
                if (shop != null) all.add(new Trip(b.id, shop.id, 0.3 * m.staff(b), false));
            }
        }
        lineRiders.forEach((id, n) -> {
            LineStats s = lines.get(id);
            lines.put(id, new LineStats(s.running(), s.coaches(), n, s.length(), s.route()));
        });
        // The coaches drive their routes all day.
        for (LineStats s : lines.values()) {
            for (RoadRouter.Leg leg : s.route()) flow.merge(leg.segment(), s.coaches() * COACH_TRIPS, Double::sum);
        }

        // Same origin, destination and purpose: one trip with the summed weight.
        Map<String, Trip> merged = new LinkedHashMap<>();
        for (Trip t : all) merged.merge(t.from + ">" + t.to + (t.commute ? "c" : "e"), t,
                (p, q) -> new Trip(p.from, p.to, p.weight + q.weight, p.commute));
        List<Trip> unique = new ArrayList<>(merged.values());
        // Incremental assignment: a quarter of every trip at a time, each round seeing the jams of the last.
        Map<Trip, List<RoadRouter.Leg>> routes = new java.util.IdentityHashMap<>();
        for (int round = 0; round < ROUNDS; round++) {
            for (Trip t : unique) {
                RoadRouter.Access a = access(byId.get(t.from)), b = access(byId.get(t.to));
                if (a == null || b == null) continue;
                List<RoadRouter.Leg> legs = router.route(a, b, this::time);
                if (legs == null) continue;
                routes.put(t, legs);
                for (RoadRouter.Leg leg : legs) flow.merge(leg.segment(), t.weight / ROUNDS, Double::sum);
            }
        }
        double free = 0, actual = 0;
        for (Map.Entry<Trip, List<RoadRouter.Leg>> e : routes.entrySet()) {
            Trip t = e.getKey();
            double f = 0, a = 0;
            for (RoadRouter.Leg leg : e.getValue()) {
                RoadSegment s = m.roads.segment(leg.segment());
                if (s == null) continue;
                double part = Math.max(0.05, Math.abs(leg.t1() - leg.t0()));
                f += router.freeTime(s) * part;
                a += time(s) * part;
            }
            free += f * t.weight;
            actual += a * t.weight;
            trips += (int) Math.ceil(t.weight);
            if (t.commute) {
                double[] c = commute.computeIfAbsent(t.from, k -> new double[2]);
                c[0] += f * t.weight;
                c[1] += a * t.weight;
            }
        }
        if (actual > 0) flowPercent = (int) Math.round(100 * free / actual);
    }

    /** The whole loop of a line, stop to stop and back to the first, or null if a stop can't be reached. */
    private @Nullable List<RoadRouter.Leg> loop(CoachLine line) {
        if (line.stops().size() < 2) return null;
        List<RoadRouter.Leg> route = new ArrayList<>();
        for (int i = 0; i < line.stops().size(); i++) {
            RoadRouter.Access a = line.access(router, i), b = line.access(router, (i + 1) % line.stops().size());
            if (a == null || b == null) return null;
            List<RoadRouter.Leg> legs = router.route(a, b, router::freeTime);
            if (legs == null) return null;
            route.addAll(legs);
        }
        return route;
    }

    /** The line this commuter rides, or null if they drive: home and work each by a stop of the same running line. */
    private @Nullable Integer ridable(Building home, Building work, List<CoachLine> coachLines, double strength, String who) {
        for (CoachLine line : coachLines) {
            LineStats s = lines.get(line.id());
            if (s == null || !s.running()) continue;
            int near = nearestStop(home, line), far = nearestStop(work, line);
            if (near < 0 || far < 0 || near == far) continue;
            // Not everyone takes the coach: a steady share, by name, so the same people ride every day.
            if (Math.floorMod(who.hashCode(), 100) < RIDER_SHARE * 100 * Math.min(1.25, strength)) return line.id();
        }
        return null;
    }

    private static int nearestStop(Building b, CoachLine line) {
        int best = -1;
        double bestD = STOP_REACH;
        for (int i = 0; i < line.stops().size(); i++) {
            var p = line.stops().get(i).marker();
            double d = Math.hypot(p.getX() + 0.5 - b.centerX(), p.getZ() + 0.5 - b.centerZ());
            if (d <= bestD) { bestD = d; best = i; }
        }
        return best;
    }

    private static @Nullable Building nearest(Building from, List<Building> options) {
        Building best = null;
        double bestD = Double.MAX_VALUE;
        for (Building o : options) {
            double d = from.distanceTo(o);
            if (d < bestD) { bestD = d; best = o; }
        }
        return best;
    }

    /** Where a building meets its road: just in front of its lot. */
    public RoadRouter.@Nullable Access access(@Nullable Building b) {
        if (b == null) return null;
        return access.computeIfAbsent(b.id, k -> {
            double reach = b.deep * 4 + 3;
            return router.access(b.centerX() + b.front.getStepX() * reach, b.centerZ() + b.front.getStepZ() * reach, 8);
        });
    }

    /** Trips a road could carry before it slows: per day, per direction pair. */
    public static double capacity(RoadType type) {
        return switch (type) {
            case LANE -> 40;
            case STREET -> 100;
            case AVENUE -> 260;
        };
    }

    /** Time to drive a whole segment with today's traffic. */
    private double time(RoadSegment s) {
        double r = ratio(s);
        return router.freeTime(s) * (1 + 0.15 * r * r * r * r);
    }

    public double flow(int segment) { return flow.getOrDefault(segment, 0.0); }

    /** Flow over capacity: under 0.5 clear, around 1 busy, above 1.2 jammed. */
    public double ratio(RoadSegment s) {
        return flow(s.id()) / capacity(s.type());
    }

    /** How long a home's commutes take against an empty road (1.0 = no delay), or 1 if nobody drives. */
    public double commuteDelay(Building home) {
        double[] c = commute.get(home.id);
        return c == null || c[0] <= 0 ? 1 : c[1] / c[0];
    }

    /** Cities: Skylines' "traffic flow": free-flow time over actual time for all trips, in percent. */
    public int flowPercent() { return flowPercent; }

    public int trips() { return trips; }

    /** Commuters on coaches. */
    public int riders() { return riders; }

    public Map<Integer, LineStats> lines() { return lines; }

    public @Nullable LineStats line(int id) { return lines.get(id); }

    public RoadRouter router() { return router; }

    /** Whether a home or workplace has a stop of a running line within reach. */
    public boolean nearStop(Building b, List<CoachLine> coachLines) {
        for (CoachLine line : coachLines) {
            LineStats s = lines.get(line.id());
            if (s != null && s.running() && nearestStop(b, line) >= 0) return true;
        }
        return false;
    }
}
