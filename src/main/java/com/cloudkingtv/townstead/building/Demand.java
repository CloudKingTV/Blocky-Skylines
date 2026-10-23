package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.Collection;
import net.minecraft.util.Mth;

/**
 * Demand for homes, shops, workshops and offices (the R, C, I and O bars), 0-100, from the town's buildings.
 * Deliberately simple and explainable: homes follow jobs, shops and workshops follow population, offices follow a
 * bigger population. Sites under construction count, so a town doesn't over-build while they go up. Abandoned
 * buildings count for nothing. Offices follow the well-educated workers the town has, and taxes push demand down
 * (above 9%) or up (below it), three points per percent.
 */
public record Demand(int residential, int commercial, int industrial, int office, int population, int plannedPeople, int workers,
                     int shopJobs, int workshopJobs, int officeJobs, int civicJobs, int educated) {
    /** Average household size, for planning (actual residents come from {@link Residents}). */
    public static final int PEOPLE_PER_HOUSEHOLD = 3;
    /** Demand a zone needs before a new site starts. */
    public static final int GROWTH_THRESHOLD = 15;

    /** Tax at which demand is neither pushed up nor down. */
    public static final int NEUTRAL_TAX = 9;

    /** Without education or tax information: offices follow population, taxes at the neutral rate. */
    public static Demand of(Collection<Building> buildings) {
        return of(buildings, -1, null);
    }

    /**
     * @param educated well-educated adults living in town (-1: unknown, offices follow population)
     * @param taxes    tax per kind (homes, shops, workshops, offices), or null for the neutral rate
     */
    public static Demand of(Collection<Building> buildings, int educated, int @org.jspecify.annotations.Nullable [] taxes) {
        return of(buildings, educated, taxes, 0, 0);
    }

    /** {@code shopPush}, {@code workshopPush}: extra demand from policies (Market Days, Craftsmen's Relief). */
    public static Demand of(Collection<Building> buildings, int educated, int @org.jspecify.annotations.Nullable [] taxes, int shopPush, int workshopPush) {
        int households = 0, population = 0, jc = 0, ji = 0, jo = 0, jv = 0;
        for (Building b : buildings) {
            if (b.state == Building.State.ABANDONED) continue;
            households += b.households;
            if (b.working()) population += Residents.people(b);
            switch (b.zone.need) {
                case SHOPS -> jc += b.jobs;
                case WORKSHOPS -> ji += b.jobs;
                case OFFICES -> jo += b.jobs;
                case CIVIC -> jv += b.jobs;
                default -> {}
            }
        }
        int people = households * PEOPLE_PER_HOUSEHOLD;
        int workers = people / 2;
        int[] t = taxes == null ? new int[] {NEUTRAL_TAX, NEUTRAL_TAX, NEUTRAL_TAX, NEUTRAL_TAX} : taxes;
        int easy = com.cloudkingtv.townstead.Difficulty.current().demand;
        int r = Mth.clamp(40 + (jc + ji + jo + jv - workers) * 3 + taxPush(t[0]) + easy, 0, 100);
        int c = Mth.clamp((int) Math.round((people / 6.0 - jc) * 6) + taxPush(t[1]) + shopPush + easy, 0, 100);
        int i = Mth.clamp((int) Math.round((people / 5.0 - ji) * 5) + 10 + taxPush(t[2]) + workshopPush + easy, 0, 100);
        double officeRoom = educated < 0 ? people / 8.0 - jo : educated * 0.75 - jo;
        int o = people < ZoneType.OFFICE.minPopulation ? 0 : Mth.clamp((int) Math.round(officeRoom * 5) + taxPush(t[3]) + easy, 0, 100);
        return new Demand(r, c, i, o, population, people, workers, jc, ji, jo, jv, Math.max(0, educated));
    }

    private static int taxPush(int tax) {
        return (NEUTRAL_TAX - tax) * 3;
    }

    public int of(ZoneType.Need need) {
        return switch (need) {
            case HOMES -> residential;
            case SHOPS -> commercial;
            case WORKSHOPS -> industrial;
            case OFFICES -> office;
            case CIVIC -> 0;
        };
    }

    /** Demand for a zone, or 0 while the town is too small for it. */
    public int of(ZoneType zone) {
        return plannedPeople < zone.minPopulation ? 0 : of(zone.need);
    }
}
