package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadRaster;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.road.RoadType;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.service.Utilities;
import com.cloudkingtv.townstead.sim.Happiness;
import com.cloudkingtv.townstead.sim.TownModel;
import com.cloudkingtv.townstead.zone.ZoneRules;
import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Why buildings level up or get abandoned. Every rule is a plain, checkable fact about the town ("a shop within
 * 32 blocks"), shown in the building panel with a tick or a cross, so players can see what's missing, which is the
 * Cities: Skylines lesson. Shared by server (decides) and client (explains), both through a {@link TownModel}.
 */
public final class Progression {
    /** Ticks a building must stand at its level before the next (at growth speed 1). */
    public static final long[] LEVEL_TIME = {0, 6000, 12000};
    /** Ticks of continuous trouble before a building is abandoned (at growth speed 1). */
    public static final int ABANDON_AFTER = 12000;

    public record Factor(Component label, boolean met) {}

    /** Land value homes and shops need for level 3. */
    public static final int LEVEL_3_LAND_VALUE = 50;
    /** Well-educated adults the town needs before offices reach level 3 (every office job needs one). */
    public static final int LEVEL_3_EDUCATED = 20;

    /** What the building needs for its next level (empty at the top level). */
    public static List<Factor> nextLevel(Building b, TownModel m, long now, double speed) {
        List<Factor> out = new ArrayList<>();
        if (b.level >= Building.MAX_LEVEL || b.civic()) return out;
        Collection<Building> town = m.town;
        int next = b.level + 1;
        long need = (long) (LEVEL_TIME[b.level] / speed);
        long settled = b.state == Building.State.ACTIVE ? now - b.activeSince : 0;
        long left = Math.max(0, need - settled);
        out.add(new Factor(left == 0 ? Component.translatable("level.townstead.settled")
                : Component.translatable("level.townstead.settle_for", (left + 1199) / 1200), left == 0));
        out.add(new Factor(Component.translatable("level.townstead.power"), m.util.power(b)));
        out.add(new Factor(Component.translatable("level.townstead.water"), m.util.water(b)));
        int want = next >= 3 ? Happiness.LEVEL_3 : Happiness.LEVEL_2, happy = m.happiness(b).total();
        out.add(new Factor(Component.translatable("level.townstead.happiness", want, happy), happy >= want));
        if (next >= 3) {
            for (ServiceType s : Utilities.levelThreeServices(b.zone)) {
                out.add(new Factor(Component.translatable("level.townstead.covered", s.displayName()), m.covered(b, s)));
            }
        }
        switch (b.zone.need) {
            case CIVIC -> {}
            case HOMES -> {
                out.add(new Factor(Component.translatable("level.townstead.shop_near", 32), nearby(b, town, 32, ZoneType.Need.SHOPS) > 0));
                if (next >= 3) {
                    boolean work = nearby(b, town, 64, ZoneType.Need.WORKSHOPS) + nearby(b, town, 64, ZoneType.Need.OFFICES) > 0;
                    out.add(new Factor(Component.translatable("level.townstead.work_near", 64), work));
                }
            }
            case SHOPS -> {
                int customers = next >= 3 ? 25 : 10, have = m.residentsNear(b, 32);
                out.add(new Factor(Component.translatable("level.townstead.customers", customers, 32, have), have >= customers));
            }
            case WORKSHOPS -> {
                int shops = next >= 3 ? 2 : 1, have = count(town, ZoneType.Need.SHOPS);
                out.add(new Factor(Component.translatable("level.townstead.shops_sell", shops, have), have >= shops));
            }
            case OFFICES -> {
                int size = next >= 3 ? 80 : 40, have = m.demand.population();
                out.add(new Factor(Component.translatable("level.townstead.town_size", size, have), have >= size));
                if (next >= 3) out.add(new Factor(Component.translatable("level.townstead.educated", LEVEL_3_EDUCATED, m.educated),
                        m.educated >= LEVEL_3_EDUCATED));
            }
        }
        if (next >= 3 && (b.zone.need == ZoneType.Need.HOMES || b.zone.need == ZoneType.Need.SHOPS)) {
            int lv = m.fields.landValue(b);
            out.add(new Factor(Component.translatable("level.townstead.land_value", LEVEL_3_LAND_VALUE, lv), lv >= LEVEL_3_LAND_VALUE));
        }
        if (next >= 3) {
            RoadType road = frontRoad(b, m.roads);
            out.add(new Factor(Component.translatable("level.townstead.big_road"), road == RoadType.STREET || road == RoadType.AVENUE));
        }
        return out;
    }

    public static boolean canLevel(List<Factor> factors) {
        return !factors.isEmpty() && factors.stream().allMatch(Factor::met);
    }

    /** What's wrong for this building right now, or null if nothing is. */
    public static @Nullable Component trouble(Building b, TownModel m) {
        Map<Long, RoadRaster.Cell> cols = ZoneRules.roadColumns(m.roads, b.city);
        if (ZoneRules.frontage(b.cellX, b.cellZ, cols, m.tiles) == null) return Component.translatable("trouble.townstead.no_road");
        if (b.civic()) return null;
        // Level 2 and up was built for power and water: losing them is trouble.
        if (b.level >= 2 && !m.util.power(b)) return Component.translatable("trouble.townstead.no_power");
        if (b.level >= 2 && !m.util.water(b)) return Component.translatable("trouble.townstead.no_water");
        Demand d = m.demand;
        int jobs = d.shopJobs() + d.workshopJobs() + d.officeJobs() + d.civicJobs();
        Component why = switch (b.zone.need) {
            case HOMES -> d.workers() > jobs * 3 / 2 + 9 ? Component.translatable("trouble.townstead.no_jobs") : null;
            case SHOPS -> d.plannedPeople() < d.shopJobs() ? Component.translatable("trouble.townstead.no_customers") : null;
            case WORKSHOPS -> d.plannedPeople() < d.workshopJobs() * 6 / 5 ? Component.translatable("trouble.townstead.no_workers") : null;
            case OFFICES -> m.staff(b) * 2 < b.jobs ? Component.translatable("trouble.townstead.no_educated") : null;
            case CIVIC -> null;
        };
        if (why != null) return why;
        Happiness h = m.happiness(b);
        if (h.total() < Happiness.UNHAPPY) {
            Happiness.Factor worst = h.worst();
            return Component.translatable("trouble.townstead.unhappy", worst == null ? Component.empty() : worst.label());
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private static double dist(Building a, Building b) {
        return a.distanceTo(b);
    }

    private static int nearby(Building b, Collection<Building> town, double radius, ZoneType.Need need) {
        int n = 0;
        for (Building o : town) if (o != b && o.zone.need == need && o.working() && dist(b, o) <= radius) n++;
        return n;
    }

    private static int count(Collection<Building> town, ZoneType.Need need) {
        int n = 0;
        for (Building o : town) if (o.zone.need == need && o.working()) n++;
        return n;
    }

    /** The type of road the building faces, or null. */
    public static @Nullable RoadType frontRoad(Building b, RoadNetwork roads) {
        Map<Long, RoadRaster.Cell> cols = ZoneRules.roadColumns(roads, b.city);
        Direction d = b.front;
        int x0 = b.cellX * ZoneRules.CELL, z0 = b.cellZ * ZoneRules.CELL;
        for (int k = 0; k <= ZoneRules.REACH + 1; k++) {
            for (int i = 0; i < ZoneRules.CELL; i++) {
                int[] p = ZoneRules.along(d, i, k);
                RoadRaster.Cell c = cols.get(RoadRaster.key(x0 + p[0], z0 + p[1]));
                if (c == null || c.owner() < 0) continue;
                RoadSegment s = roads.segment(c.owner());
                if (s != null) return s.type();
            }
        }
        return null;
    }

    private Progression() {}
}
