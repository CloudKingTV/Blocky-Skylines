package com.cloudkingtv.townstead.sim;

import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.road.RoadType;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.zone.ZoneRules;
import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;

/**
 * Pollution, noise and land value per 8x8 cell (0-100), as in Cities: Skylines' info views. Each comes from
 * sources that fade linearly to nothing at their radius: workshops and the compost yard pollute; shops, workshops,
 * the fire station and roads make noise (more the busier they are); parks, services, shops and the Town Hall raise land value while
 * pollution and noise lower it. Pure and cheap, so server and client compute the same numbers.
 */
public final class Fields {
    /** A point source: {@code strength} at the centre, nothing beyond {@code radius} blocks. */
    record Source(double x, double z, double strength, double radius) {
        double at(double px, double pz) {
            double d = Math.hypot(px - x, pz - z);
            return d >= radius ? 0 : strength * (1 - d / radius);
        }
    }

    /** Land value of a lot with nothing around it. */
    public static final int BASE_LAND_VALUE = 30;

    private final List<Source> pollution = new ArrayList<>(), noise = new ArrayList<>(), roadNoise = new ArrayList<>(), value = new ArrayList<>();
    private final Map<Long, int[]> cache = new HashMap<>();
    private com.cloudkingtv.townstead.district.Civics civics = com.cloudkingtv.townstead.district.Civics.NONE;

    /**
     * @param radius how far each active service reaches (its department budget scales it)
     */
    public static Fields of(UUID city, Collection<Building> town, RoadNetwork roads, BlockPos townHall, ToDoubleFunction<ServiceType> radius,
                            Traffic traffic, com.cloudkingtv.townstead.district.Civics civics) {
        Fields f0 = null; // placeholder = civics;
        Fields f = new Fields();
        f.value.add(new Source(townHall.getX() + 0.5, townHall.getZ() + 0.5, 12, 72));
        for (Building b : town) {
            if (b.state != Building.State.ACTIVE && !b.upgrading) continue;
            double x = b.centerX(), z = b.centerZ();
            int l = b.level - 1;
            if (b.civic()) {
                ServiceType s = b.serviceType();
                switch (s) {
                    case COMPOST_YARD -> f.pollution.add(new Source(x, z, 25, 24));
                    case FIRE_STATION -> f.noise.add(new Source(x, z, 12, 24));
                    default -> {}
                }
                switch (s) {
                    case PARK -> f.value.add(new Source(x, z, 25, 40));
                    case FOUNDERS_FOUNTAIN, CLOCK_TOWER, CHAPEL -> f.value.add(new Source(x, z, 22, radius.applyAsDouble(s)));
                    case GREAT_LIBRARY, CATHEDRAL, CASTLE_KEEP -> f.value.add(new Source(x, z, 30, radius.applyAsDouble(s)));
                    case CLINIC, SCHOOL, GUARD_HOUSE, FIRE_STATION -> f.value.add(new Source(x, z, 6, radius.applyAsDouble(s)));
                    default -> {}
                }
                continue;
            }
            switch (b.zone.need) {
                case WORKSHOPS -> {
                    double dirty = civics.has(b, com.cloudkingtv.townstead.district.Policy.CLEAN_HEARTHS) ? 0.5 : 1;
                    double quiet = civics.has(b, com.cloudkingtv.townstead.district.Policy.QUIET_NIGHTS) ? 0.6 : 1;
                    var spec = com.cloudkingtv.townstead.district.Specialization.byKey(b.spec);
                    if (spec == com.cloudkingtv.townstead.district.Specialization.FARMING) dirty *= 0.15;
                    if (spec == com.cloudkingtv.townstead.district.Specialization.FORESTRY) dirty *= 0.6;
                    // Quarries: stone dust and the crack of blasting carry further than any forge.
                    boolean quarry = spec == com.cloudkingtv.townstead.district.Specialization.QUARRY;
                    if (quarry) dirty *= 1.3;
                    f.pollution.add(new Source(x, z, (30 + 15 * l) * dirty, (24 + 8 * l) * (quarry ? 1.25 : 1)));
                    f.noise.add(new Source(x, z, 15 * quiet * (quarry ? 2 : 1), quarry ? 30 : 20));
                }
                case SHOPS -> {
                    boolean big = b.zone == ZoneType.COMMERCIAL_HIGH;
                    double loud = (civics.has(b, com.cloudkingtv.townstead.district.Policy.QUIET_NIGHTS) ? 0.6 : 1) * (civics.has(b, com.cloudkingtv.townstead.district.Policy.MARKET_DAYS) ? 1.4 : 1)
                            * (com.cloudkingtv.townstead.district.Specialization.byKey(b.spec) == com.cloudkingtv.townstead.district.Specialization.LEISURE ? 1.5 : 1);
                    f.noise.add(new Source(x, z, (big ? 20 : 12) * loud, big ? 24 : 16));
                    f.value.add(new Source(x, z, 4, 24));
                }
                default -> {}
            }
        }
        for (RoadSegment s : roads.segments()) {
            if (!s.city().equals(city)) continue;
            // Busier roads are louder: an empty road makes 40% of its noise, a jammed one up to 160%.
            double busy = Mth.clamp(traffic.ratio(s), 0, 1.5);
            double loud = (s.type() == RoadType.AVENUE ? 16 : s.type() == RoadType.STREET ? 8 : 3) * (0.4 + 0.8 * busy);
            long mid = ZoneRules.cellKey(Math.floorDiv((int) s.curve().x(0.5), ZoneRules.CELL), Math.floorDiv((int) s.curve().z(0.5), ZoneRules.CELL));
            if (civics.has(mid, com.cloudkingtv.townstead.district.Policy.QUIET_NIGHTS)) loud *= 0.6;
            double reach = s.type().halfWidth() + (s.type() == RoadType.AVENUE ? 10 : s.type() == RoadType.STREET ? 7 : 4);
            int n = Math.max(1, (int) Math.ceil(s.curve().length() / 4));
            for (int i = 0; i <= n; i++) {
                double t = (double) i / n;
                f.roadNoise.add(new Source(s.curve().x(t), s.curve().z(t), loud, reach));
            }
        }
        return f;
    }

    private int[] cell(int cx, int cz) {
        return cache.computeIfAbsent(ZoneRules.cellKey(cx, cz), k -> {
            double x = cx * ZoneRules.CELL + 4, z = cz * ZoneRules.CELL + 4;
            double p = 0, n = 0, road = 0, v = BASE_LAND_VALUE;
            for (Source s : pollution) p += s.at(x, z);
            for (Source s : noise) n += s.at(x, z);
            // Roads: the loudest stretch counts, not every sample along it.
            for (Source s : roadNoise) road = Math.max(road, s.at(x, z));
            n += road;
            for (Source s : value) v += s.at(x, z);
            // Policies on the cell's district (or the town): lanterns, gardens and the old town's charm.
            long key = ZoneRules.cellKey(cx, cz);
            if (civics.has(key, com.cloudkingtv.townstead.district.Policy.LANTERN_STREETS)) v += 6;
            if (civics.has(key, com.cloudkingtv.townstead.district.Policy.GARDEN_TOWN)) v += 3;
            if (civics.has(key, com.cloudkingtv.townstead.district.Policy.OLD_TOWN)) v += 8;
            int pi = Mth.clamp((int) Math.round(p), 0, 100), ni = Mth.clamp((int) Math.round(n), 0, 100);
            int vi = Mth.clamp((int) Math.round(v - 0.6 * pi - 0.4 * ni), 0, 100);
            return new int[] {pi, ni, vi};
        });
    }

    public int pollution(int cx, int cz) { return cell(cx, cz)[0]; }
    public int noise(int cx, int cz) { return cell(cx, cz)[1]; }
    public int landValue(int cx, int cz) { return cell(cx, cz)[2]; }

    public int pollution(Building b) { return pollution(b.cellX, b.cellZ); }
    public int noise(Building b) { return noise(b.cellX, b.cellZ); }
    public int landValue(Building b) { return landValue(b.cellX, b.cellZ); }
}
