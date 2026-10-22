package com.cloudkingtv.townstead.economy;

import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.road.RoadType;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.sim.Happiness;
import com.cloudkingtv.townstead.sim.TownModel;
import com.cloudkingtv.townstead.zone.ZoneType;

/**
 * A town's money per Minecraft day, line by line, as the budget panel shows it. Taxes: each household and each
 * filled job has a taxable value that grows with the building's level (and, for homes, with land value); the rate
 * for its kind takes a share. Upkeep: every service and utility at its department's budget, plus the roads by
 * length, and the coaches running on lines (which earn fares). Exports: the industry's surplus beyond what the
 * storehouse holds, sold. Loans: their daily payments. All in hundredths of an emerald.
 */
public final class Finance {
    /** Taxable value per household per day, at level 1 and land value 50. */
    public static final int HOUSEHOLD_VALUE = 400;
    /** Taxable value per filled job per day at level 1: shops, workshops, offices. */
    public static final int SHOP_JOB_VALUE = 500, WORKSHOP_JOB_VALUE = 400, OFFICE_JOB_VALUE = 700;
    private static final double[] LEVEL_MULTIPLIER = {1.0, 1.5, 2.25};

    /** Fare per coach rider per day, and a coach's running cost at a 100% transport budget. */
    public static final int FARE = 4, COACH_UPKEEP = 120;

    public record Report(long[] taxes, long[] upkeep, long roads, long loans, long fares, long policies, long exports) {
        public long income() {
            long n = fares + exports;
            for (long t : taxes) n += t;
            return n;
        }

        public long expenses() {
            long n = roads + loans + policies;
            for (long u : upkeep) n += u;
            return n;
        }

        public long net() { return income() - expenses(); }
    }

    public static Report daily(TownModel m) {
        long[] taxes = new long[Budget.TAX_KINDS];
        long[] upkeep = new long[ServiceType.Department.values().length];
        for (Building b : m.town) {
            if (b.civic()) {
                if (b.state == Building.State.ACTIVE || b.upgrading) {
                    ServiceType s = b.serviceType();
                    upkeep[s.department.ordinal()] += (long) s.upkeep * m.budget.budget(s.department) / 100;
                }
                continue;
            }
            if (!b.working()) continue;
            int kind = Happiness.taxKind(b);
            taxes[kind] += Math.round(value(b, m) * m.budget.tax(kind) / 100.0 * taxShare(b, m));
        }
        long roads = 0;
        for (RoadSegment s : m.roads.segments()) {
            if (s.city().equals(m.city)) roads += Math.round(s.curve().length() * roadUpkeep(s.type()));
        }
        long loans = 0;
        for (Loan l : m.budget.loans) loans += Math.min(l.daily(), l.owed());
        // Coaches cost their upkeep (from the transport budget) and earn fares.
        int transport = ServiceType.Department.TRANSPORT.ordinal();
        for (var line : m.traffic.lines().values()) {
            upkeep[transport] += (long) line.coaches() * COACH_UPKEEP * m.budget.budget(ServiceType.Department.TRANSPORT) / 100;
        }
        long fares = m.civics.policies().contains(com.cloudkingtv.townstead.district.Policy.FREE_COACHES) ? 0 : (long) m.traffic.riders() * FARE;
        return new Report(taxes, upkeep, roads, loans, fares, policyCost(m), Math.round(m.industry().exportValue()));
    }

    /** How much of its tax a building pays under the town's policies and its specialisation. */
    static double taxShare(Building b, TownModel m) {
        double share = 1;
        if (b.zone.need == ZoneType.Need.WORKSHOPS && m.civics.has(b, com.cloudkingtv.townstead.district.Policy.CRAFT_RELIEF)) share *= 0.5;
        if (b.zone.need == ZoneType.Need.SHOPS && m.civics.has(b, com.cloudkingtv.townstead.district.Policy.QUIET_NIGHTS)) share *= 0.9;
        if (com.cloudkingtv.townstead.district.Specialization.byKey(b.spec) == com.cloudkingtv.townstead.district.Specialization.LEISURE) share *= 1.3;
        return share;
    }

    /** What the town's policies cost a day: smoke hoods per workshop job, lanterns per block of road, flowers per household, classes per adult. */
    public static long policyCost(TownModel m) {
        double cost = 0;
        for (Building b : m.town) {
            if (!b.working()) continue;
            if (b.zone.need == ZoneType.Need.WORKSHOPS && m.civics.has(b, com.cloudkingtv.townstead.district.Policy.CLEAN_HEARTHS)) cost += 3.0 * m.staff(b);
            if (b.zone.residential()) {
                if (m.civics.has(b, com.cloudkingtv.townstead.district.Policy.GARDEN_TOWN)) cost += b.households;
                if (m.civics.has(b, com.cloudkingtv.townstead.district.Policy.EVENING_CLASSES)) {
                    for (var h : com.cloudkingtv.townstead.building.Residents.households(b)) {
                        for (var p : h.members()) if (p.stage() == com.cloudkingtv.townstead.building.Residents.Stage.ADULT) cost += 2;
                    }
                }
            }
        }
        for (RoadSegment s : m.roads.segments()) {
            if (!s.city().equals(m.city)) continue;
            long mid = com.cloudkingtv.townstead.zone.ZoneRules.cellKey(Math.floorDiv((int) s.curve().x(0.5), 8), Math.floorDiv((int) s.curve().z(0.5), 8));
            if (m.civics.has(mid, com.cloudkingtv.townstead.district.Policy.LANTERN_STREETS)) cost += 0.5 * s.curve().length();
        }
        return Math.round(cost);
    }

    /** Taxable value of a building per day, before the rate. */
    public static double value(Building b, TownModel m) {
        double level = LEVEL_MULTIPLIER[Math.max(0, Math.min(LEVEL_MULTIPLIER.length - 1, b.level - 1))];
        if (b.zone.residential()) {
            double land = 0.8 + m.fields.landValue(b) / 250.0; // 1.0 at land value 50
            return b.households * HOUSEHOLD_VALUE * level * land;
        }
        int perJob = b.zone.need == ZoneType.Need.SHOPS ? SHOP_JOB_VALUE : b.zone.need == ZoneType.Need.WORKSHOPS ? WORKSHOP_JOB_VALUE : OFFICE_JOB_VALUE;
        return m.staff(b) * perJob * level;
    }

    /** Road upkeep per block of length per day. */
    public static double roadUpkeep(RoadType type) {
        return switch (type) {
            case LANE -> 1;
            case STREET -> 2;
            case AVENUE -> 4;
        };
    }

    private Finance() {}
}
