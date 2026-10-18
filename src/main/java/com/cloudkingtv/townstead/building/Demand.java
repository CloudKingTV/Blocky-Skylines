package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.Collection;
import net.minecraft.util.Mth;

/**
 * Residential / commercial / industrial demand, 0-100, from the town's buildings. Deliberately simple and
 * explainable: homes follow jobs, shops follow population, workshops follow population too. Buildings still under
 * construction count, so a town doesn't over-build while sites are going up.
 */
public record Demand(int residential, int commercial, int industrial, int population, int households, int workers,
                     int shopJobs, int workshopJobs) {
    public static final int PEOPLE_PER_HOUSEHOLD = 3;
    /** Demand a zone needs before a new site starts. */
    public static final int GROWTH_THRESHOLD = 15;

    public static Demand of(Collection<Building> buildings) {
        int h = 0, hActive = 0, jc = 0, ji = 0;
        for (Building b : buildings) {
            h += b.households;
            if (b.state == Building.State.ACTIVE) hActive += b.households;
            if (b.zone == ZoneType.COMMERCIAL) jc += b.jobs;
            if (b.zone == ZoneType.INDUSTRIAL) ji += b.jobs;
        }
        int people = h * PEOPLE_PER_HOUSEHOLD;
        int workers = people / 2;
        int r = Mth.clamp(40 + (jc + ji - workers) * 3, 0, 100);
        int c = Mth.clamp((int) Math.round((people / 6.0 - jc) * 6), 0, 100);
        int i = Mth.clamp((int) Math.round((people / 5.0 - ji) * 5) + 10, 0, 100);
        return new Demand(r, c, i, hActive * PEOPLE_PER_HOUSEHOLD, h, workers, jc, ji);
    }

    public int of(ZoneType zone) {
        return switch (zone) {
            case RESIDENTIAL -> residential;
            case COMMERCIAL -> commercial;
            case INDUSTRIAL -> industrial;
        };
    }
}
