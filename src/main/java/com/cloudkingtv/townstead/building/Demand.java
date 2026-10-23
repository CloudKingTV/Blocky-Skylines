package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.Collection;
import net.minecraft.util.Mth;

/**
 * Demand for homes, shops, workshops and offices (the R, C, I and O bars), 0-100, from the town's buildings.
 * Deliberately simple and explainable: homes follow jobs per worker, shops and workshops follow the working population
 * (two fifths and nearly half of it), offices follow the well educated. Sites under construction count, so a town doesn't over-build while they go up. Abandoned
 * buildings count for nothing. Offices follow the well-educated workers the town has, and taxes push demand down
 * (above 9%) or up (below it), three points per percent.
 */
public record Demand(int residential, int commercial, int industrial, int office, int population, int plannedPeople, int workers,
                     int shopJobs, int workshopJobs, int officeJobs, int civicJobs, int educated) {
    /** Average household size, for planning (actual residents come from {@link Residents}). */
    public static final int PEOPLE_PER_HOUSEHOLD = 3;
    /** Working-age adults a household brings, and the shares of them shops and workshops are planned to employ. */
    public static final int WORKERS_PER_HOUSEHOLD = 2;
    public static final double SHOP_SHARE = 0.4, WORKSHOP_SHARE = 0.45;

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
        // Two working-age adults a household (as Residents makes them); shops and workshops between them
        // employ most of them, offices and services the rest.
        int workers = households * WORKERS_PER_HOUSEHOLD;
        int[] t = taxes == null ? new int[] {NEUTRAL_TAX, NEUTRAL_TAX, NEUTRAL_TAX, NEUTRAL_TAX} : taxes;
        int easy = com.cloudkingtv.townstead.Difficulty.current().demand;
        // Relative to the town's size, so the bars mean the same in a hamlet and a city: homes follow jobs per
        // worker; shops, workshops and offices follow how far their jobs fall short of their share.
        int jobs = jc + ji + jo + jv;
        int r = Mth.clamp((int) Math.round(40 + 150.0 * (jobs - workers) / Math.max(workers, 20)) + taxPush(t[0]) + easy, 0, 100);
        int c = Mth.clamp(shortfall(workers * SHOP_SHARE, jc) + taxPush(t[1]) + shopPush + easy, 0, 100);
        int i = Mth.clamp(shortfall(workers * WORKSHOP_SHARE, ji) + 3 + taxPush(t[2]) + workshopPush + easy, 0, 100);
        double officeTarget = educated < 0 ? people / 8.0 : educated * 0.6; // not every graduate wants a desk
        int o = people < ZoneType.OFFICE.minPopulation ? 0 : Mth.clamp(shortfall(officeTarget, jo) + taxPush(t[3]) + easy, 0, 100);
        return new Demand(r, c, i, o, population, people, workers, jc, ji, jo, jv, Math.max(0, educated));
    }

    /** 10 when a kind of job meets its target (just under the growth threshold), up by 20 for every tenth short. */
    private static int shortfall(double target, int have) {
        return (int) Math.round(10 + 200 * (target - have) / Math.max(target, 8));
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
