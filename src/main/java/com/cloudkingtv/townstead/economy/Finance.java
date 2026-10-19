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
 * length. Loans: their daily payments. All in hundredths of an emerald.
 */
public final class Finance {
    /** Taxable value per household per day, at level 1 and land value 50. */
    public static final int HOUSEHOLD_VALUE = 400;
    /** Taxable value per filled job per day at level 1: shops, workshops, offices. */
    public static final int SHOP_JOB_VALUE = 500, WORKSHOP_JOB_VALUE = 400, OFFICE_JOB_VALUE = 700;
    private static final double[] LEVEL_MULTIPLIER = {1.0, 1.5, 2.25};

    public record Report(long[] taxes, long[] upkeep, long roads, long loans) {
        public long income() {
            long n = 0;
            for (long t : taxes) n += t;
            return n;
        }

        public long expenses() {
            long n = roads + loans;
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
            taxes[kind] += Math.round(value(b, m) * m.budget.tax(kind) / 100.0);
        }
        long roads = 0;
        for (RoadSegment s : m.roads.segments()) {
            if (s.city().equals(m.city)) roads += Math.round(s.curve().length() * roadUpkeep(s.type()));
        }
        long loans = 0;
        for (Loan l : m.budget.loans) loans += Math.min(l.daily(), l.owed());
        return new Report(taxes, upkeep, roads, loans);
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
