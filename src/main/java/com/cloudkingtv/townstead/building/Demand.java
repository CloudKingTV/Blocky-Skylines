package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.Collection;
import net.minecraft.util.Mth;

/**
 * Demand for homes, shops, workshops and offices (the R, C, I and O bars), 0-100, from the town's buildings.
 * Deliberately simple and explainable: homes follow jobs, shops and workshops follow population, offices follow a
 * bigger population. Sites under construction count, so a town doesn't over-build while they go up. Abandoned
 * buildings count for nothing.
 */
public record Demand(int residential, int commercial, int industrial, int office, int population, int plannedPeople, int workers,
                     int shopJobs, int workshopJobs, int officeJobs, int civicJobs) {
    /** Average household size, for planning (actual residents come from {@link Residents}). */
    public static final int PEOPLE_PER_HOUSEHOLD = 3;
    /** Demand a zone needs before a new site starts. */
    public static final int GROWTH_THRESHOLD = 15;

    public static Demand of(Collection<Building> buildings) {
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
        int r = Mth.clamp(40 + (jc + ji + jo + jv - workers) * 3, 0, 100);
        int c = Mth.clamp((int) Math.round((people / 6.0 - jc) * 6), 0, 100);
        int i = Mth.clamp((int) Math.round((people / 5.0 - ji) * 5) + 10, 0, 100);
        int o = people < ZoneType.OFFICE.minPopulation ? 0 : Mth.clamp((int) Math.round((people / 8.0 - jo) * 5), 0, 100);
        return new Demand(r, c, i, o, population, people, workers, jc, ji, jo, jv);
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
