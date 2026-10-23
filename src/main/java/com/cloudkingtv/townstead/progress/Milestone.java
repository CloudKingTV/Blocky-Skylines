package com.cloudkingtv.townstead.progress;

import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.road.RoadType;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.List;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The town's milestones, as in Cities: Skylines: reach a population and the town earns a title, a purse of
 * emeralds, the right to buy more land, and new things to build. Everything a milestone unlocks is listed here,
 * so the planner can grey out what's still locked and say exactly what it takes.
 */
public enum Milestone {
    // One tile to buy from the start, so a town on rough ground can always find room to reach the next milestone.
    LITTLE_HAMLET(0, 0, 1,
            List.of(RoadType.LANE, RoadType.STREET),
            List.of(ZoneType.RESIDENTIAL, ZoneType.COMMERCIAL, ZoneType.INDUSTRIAL),
            List.of(ServiceType.WINDMILL, ServiceType.WELL, ServiceType.PARK)),
    WORTHY_VILLAGE(40, 40, 1,
            List.of(RoadType.AVENUE),
            List.of(),
            List.of(ServiceType.CLINIC, ServiceType.SCHOOL, ServiceType.GUARD_HOUSE, ServiceType.COACH_HOUSE, ServiceType.FOUNDERS_FOUNTAIN)),
    TINY_TOWN(100, 80, 2,
            List.of(),
            List.of(ZoneType.RESIDENTIAL_HIGH, ZoneType.COMMERCIAL_HIGH),
            List.of(ServiceType.FIRE_STATION, ServiceType.COMPOST_YARD, ServiceType.WATER_TOWER)),
    BOOM_TOWN(200, 150, 3,
            List.of(),
            List.of(ZoneType.OFFICE),
            List.of(ServiceType.WATERMILL, ServiceType.CLOCK_TOWER)),
    BUSY_TOWN(350, 250, 4,
            List.of(),
            List.of(),
            List.of(ServiceType.CHAPEL)),
    BIG_TOWN(550, 400, 6,
            List.of(),
            List.of(),
            List.of()),
    // The city milestones, on to Megalopolis.
    SMALL_CITY(800, 600, 6,
            List.of(),
            List.of(),
            List.of(ServiceType.GREAT_LIBRARY)),
    BIG_CITY(1100, 800, 6,
            List.of(),
            List.of(),
            List.of()),
    GRAND_CITY(1500, 1000, 8,
            List.of(),
            List.of(),
            List.of(ServiceType.CATHEDRAL)),
    CAPITAL_CITY(2000, 1300, 8,
            List.of(),
            List.of(),
            List.of()),
    COLOSSAL_CITY(2600, 1600, 10,
            List.of(),
            List.of(),
            List.of()),
    METROPOLIS(3300, 2000, 10,
            List.of(),
            List.of(),
            List.of(ServiceType.CASTLE_KEEP)),
    MEGALOPOLIS(4000, 3000, 12,
            List.of(),
            List.of(),
            List.of());

    /** People the town needs to reach this milestone. */
    public final int population;
    /** Emeralds paid into the treasury on reaching it. */
    public final int reward;
    /** More land tiles the town may buy from here on. */
    public final int tiles;
    public final List<RoadType> roads;
    public final List<ZoneType> zones;
    public final List<ServiceType> services;

    Milestone(int population, int reward, int tiles, List<RoadType> roads, List<ZoneType> zones, List<ServiceType> services) {
        this.population = population;
        this.reward = reward;
        this.tiles = tiles;
        this.roads = roads;
        this.zones = zones;
        this.services = services;
    }

    public String key() { return name().toLowerCase(java.util.Locale.ROOT); }

    public Component displayName() { return Component.translatable("milestone.townstead." + key()); }

    public static Milestone of(int index) {
        return values()[Math.max(0, Math.min(values().length - 1, index))];
    }

    public @Nullable Milestone next() {
        return ordinal() + 1 < values().length ? values()[ordinal() + 1] : null;
    }

    /** Tiles a town at milestone {@code reached} may own: the starting 3x3 plus every milestone's allowance. */
    public static int tileAllowance(int reached) {
        int n = 9;
        for (Milestone m : values()) if (m.ordinal() <= reached) n += m.tiles;
        return n;
    }

    /** The milestone that unlocks a road type, zone or service. */
    public static Milestone unlocking(Object thing) {
        for (Milestone m : values()) {
            if (m.roads.contains(thing) || m.zones.contains(thing) || m.services.contains(thing)) return m;
        }
        return LITTLE_HAMLET;
    }

    /** Whether a town that reached milestone {@code reached} may build this (the unlockAll setting opens everything). */
    public static boolean unlocked(int reached, Object thing) {
        return TownsteadConfig.UNLOCK_ALL.get() || unlocking(thing).ordinal() <= reached;
    }
}
