package com.cloudkingtv.townstead.sim;

import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.Demand;
import com.cloudkingtv.townstead.building.Residents;
import com.cloudkingtv.townstead.city.TilePos;
import com.cloudkingtv.townstead.economy.Budget;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.service.Utilities;
import com.cloudkingtv.townstead.zone.ZoneRules;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;

/**
 * Everything the town's rules need, worked out once from its buildings, roads and budget: power and water, service
 * reach, the land fields, who goes to school, who works where, demand and happiness. The server builds one to decide
 * (growth, levels, trouble, taxes) and the client builds the same one to explain (panel, info views, budget), so what
 * the player reads is what the simulation did.
 */
public final class TownModel {
    public final UUID city;
    public final List<Building> town;
    public final RoadNetwork roads;
    public final Budget budget;
    public final long treasury;
    public final Set<TilePos> tiles;
    public final Utilities.State util;
    public final Fields fields;
    /** Coach lines, as drawn. */
    public final List<com.cloudkingtv.townstead.transit.CoachLine> lines;
    /** Districts and policies. */
    public final com.cloudkingtv.townstead.district.Civics civics;
    /** Who drives where, how jammed each road is, and who rides the coaches. */
    public final Traffic traffic;
    /** Homes with a school in reach. */
    public final Set<Integer> schooled = new HashSet<>();
    public final Map<Integer, List<Residents.Job>> employment;
    public final Map<String, Residents.Job> jobsByPerson;
    public final Demand demand;
    /** Working-age residents, those without a job, and the well educated among them. */
    public final int adults, unemployed, educated;
    private final Map<Integer, Happiness> happiness = new HashMap<>();
    /** The storehouse as it stood (per material) and how many Town Storehouses the town has, for its industry. */
    public final Map<com.cloudkingtv.townstead.road.Material, Integer> stock;
    public final int storehouses;
    private com.cloudkingtv.townstead.industry.Industry.@org.jspecify.annotations.Nullable Day industry;
    /** Working services and utilities by type, so "is this lot in reach of a clinic" looks only at clinics. */
    private final Map<String, List<Building>> services = new HashMap<>();
    /** Working buildings by {@link #NEAR_CELL}-block square of their centre, for "what's near this lot". */
    private final Map<Long, List<Building>> near = new HashMap<>();
    private final Map<com.cloudkingtv.townstead.zone.ZoneType.Need, Integer> working = new java.util.EnumMap<>(com.cloudkingtv.townstead.zone.ZoneType.Need.class);
    private static final int NEAR_CELL = 32;

    private TownModel(UUID city, Collection<Building> town, RoadNetwork roads, Budget budget, long treasury, Set<TilePos> tiles, BlockPos townHall,
                      com.cloudkingtv.townstead.district.Civics civics, Map<com.cloudkingtv.townstead.road.Material, Integer> stock, int storehouses) {
        this.city = city;
        this.stock = stock;
        this.storehouses = storehouses;
        this.town = List.copyOf(town);
        this.roads = roads;
        this.budget = budget;
        this.treasury = treasury;
        this.tiles = tiles;
        this.civics = civics;
        this.lines = civics.lines();
        for (Building b : this.town) {
            if (b.civic() && b.state == Building.State.ACTIVE) services.computeIfAbsent(b.service, k -> new ArrayList<>()).add(b);
            if (!b.working()) continue;
            near.computeIfAbsent(nearKey(b.centerX(), b.centerZ()), k -> new ArrayList<>()).add(b);
            working.merge(b.zone.need, 1, Integer::sum);
        }
        this.util = Utilities.compute(this.town, roads, this::strength);
        for (Building b : this.town) {
            if (b.zone.residential() && (covered(b, ServiceType.SCHOOL) || civics.has(b, com.cloudkingtv.townstead.district.Policy.EVENING_CLASSES))) {
                schooled.add(b.id);
            }
        }
        this.employment = Residents.employment(this.town, schooled);
        this.jobsByPerson = Residents.jobsByPerson(employment);
        this.traffic = new Traffic(this, this.lines);
        this.fields = Fields.of(city, this.town, roads, townHall, this::radius, traffic, civics);
        int a = 0, e = 0;
        for (Building b : this.town) {
            if (!b.working() || !b.zone.residential()) continue;
            boolean school = schooled.contains(b.id);
            for (Residents.Household h : Residents.households(b)) {
                for (Residents.Person p : h.members()) {
                    if (p.stage() != Residents.Stage.ADULT) continue;
                    a++;
                    if (p.education(school) >= Residents.SKILLED) e++;
                }
            }
        }
        this.adults = a;
        this.educated = e;
        this.unemployed = Math.max(0, a - jobsByPerson.size());
        this.demand = Demand.of(this.town, e, budget.taxes,
                (civics.anywhere(com.cloudkingtv.townstead.district.Policy.MARKET_DAYS) ? 15 : 0)
                        + (this.town.stream().anyMatch(b -> b.working() && ServiceType.CATHEDRAL.key().equals(b.service)) ? 12 : 0),
                civics.anywhere(com.cloudkingtv.townstead.district.Policy.CRAFT_RELIEF) ? 15 : 0);
    }

    /**
     * What a model depends on besides money: each building's state, the road graph, lines, districts, policies and
     * land. Building progress isn't in it, so a town under construction doesn't need its model worked out again
     * for every block laid.
     */
    public static long fingerprint(Collection<Building> town, RoadNetwork roads, com.cloudkingtv.townstead.district.Civics civics, int tiles) {
        long h = 17;
        for (Building b : town) {
            h = h * 31 + b.id;
            h = h * 31 + b.state.ordinal() * 8 + b.level + (b.upgrading ? 4 : 0);
            h = h * 31 + b.households * 1000 + b.jobs;
            h = h * 31 + b.spec.hashCode();
        }
        h = h * 31 + roads.nextId();
        h = h * 31 + roads.segments().size();
        h = h * 31 + civics.lines().hashCode();
        h = h * 31 + civics.districts().hashCode();
        h = h * 31 + civics.policies().hashCode();
        h = h * 31 + tiles;
        return h;
    }

    public static TownModel of(UUID city, Collection<Building> town, RoadNetwork roads, Budget budget, long treasury,
                               Collection<TilePos> tiles, BlockPos townHall, com.cloudkingtv.townstead.district.Civics civics) {
        return new TownModel(city, town, roads, budget, treasury, Set.copyOf(tiles), townHall, civics, Map.of(), 0);
    }

    /** As above, with the storehouse's stock (item id to count) and Town Storehouses, so industry and trade can be worked out. */
    public static TownModel of(UUID city, Collection<Building> town, RoadNetwork roads, Budget budget, long treasury,
                               Collection<TilePos> tiles, BlockPos townHall, com.cloudkingtv.townstead.district.Civics civics,
                               Map<net.minecraft.resources.Identifier, Integer> stock, int storehouses) {
        return new TownModel(city, town, roads, budget, treasury, Set.copyOf(tiles), townHall, civics,
                com.cloudkingtv.townstead.industry.Industry.counts(stock), storehouses);
    }

    /** A day of the town's industry against the stock this model was made with. */
    public com.cloudkingtv.townstead.industry.Industry.Day industry() {
        if (industry == null) industry = com.cloudkingtv.townstead.industry.Industry.day(this, stock, storehouses, 1.0);
        return industry;
    }

    /** In debt: services and utilities run at three-quarter strength until the treasury is back above zero. */
    public boolean broke() {
        return treasury < 0;
    }

    /** How hard a department works: its budget (1.0 at 100%), halved while the town is broke. */
    public double strength(ServiceType s) {
        return budget.budget(s.department) / 100.0 * (broke() ? 0.75 : 1.0);
    }

    /** A service's reach in blocks: 80% at a 50% budget, 120% at 150%. */
    public double radius(ServiceType s) {
        return s.radius * (0.6 + 0.4 * strength(s));
    }

    public boolean covered(Building b, ServiceType s) {
        if (reaches(b, s)) return true;
        // Monuments that do a service's work across their (huge) reach.
        ServiceType also = switch (s) {
            case GUARD_HOUSE, FIRE_STATION -> ServiceType.CASTLE_KEEP;
            case SCHOOL -> ServiceType.GREAT_LIBRARY;
            default -> null;
        };
        return also != null && reaches(b, also);
    }

    /** Whether a working service of this type reaches the building (as {@link Utilities#covered}, from the index). */
    private boolean reaches(Building b, ServiceType s) {
        double r = radius(s);
        for (Building o : services.getOrDefault(s.key(), List.of())) if (o.distanceTo(b) <= r) return true;
        return false;
    }

    private static long nearKey(double x, double z) {
        return ((long) Math.floorDiv((int) Math.floor(x), NEAR_CELL) << 32) | (Math.floorDiv((int) Math.floor(z), NEAR_CELL) & 0xFFFFFFFFL);
    }

    public Happiness happiness(Building b) {
        return happiness.computeIfAbsent(b.id, k -> Happiness.of(b, this));
    }

    /** Whether the building's lot still faces one of the town's roads, on its land. */
    public boolean hasFrontage(Building b) {
        return frontage.computeIfAbsent(ZoneRules.cellKey(b.cellX, b.cellZ),
                k -> ZoneRules.frontage(b.cellX, b.cellZ, ZoneRules.roadColumns(roads, b.city), tiles) != null);
    }

    private final Map<Long, Boolean> frontage = new HashMap<>();

    /**
     * Works out up front what the server's reviews will ask of every building (its lot's land fields and its
     * happiness), so a model built off the server thread hands over with nothing left to work out on it.
     */
    public TownModel warm() {
        for (Building b : town) {
            hasFrontage(b);
            fields.landValue(b);
            if (!b.civic()) happiness(b);
        }
        return this;
    }

    /** Average happiness of the town's homes and workplaces, weighted by people and jobs (0-100). */
    public int townHappiness() {
        double sum = 0, weight = 0;
        for (Building b : town) {
            if (b.civic() || !b.working()) continue;
            int w = Math.max(1, b.zone.residential() ? Residents.people(b) : b.jobs);
            sum += happiness(b).total() * w;
            weight += w;
        }
        return weight == 0 ? 50 : (int) Math.round(sum / weight);
    }

    /** Workers a workplace actually has. */
    public int staff(Building b) {
        return employment.getOrDefault(b.id, List.of()).size();
    }

    /** Percent of working-age residents without a job. */
    public int unemploymentRate() {
        return adults == 0 ? 0 : unemployed * 100 / adults;
    }

    /** People living in working homes within {@code radius} blocks. */
    public int residentsNear(Building b, double radius) {
        int[] n = {0};
        near(b, radius, o -> { if (o.zone.residential()) n[0] += Residents.people(o); });
        return n[0];
    }

    /** Other working buildings of a kind within {@code radius} blocks (centre to centre). */
    public int workingNear(Building b, double radius, com.cloudkingtv.townstead.zone.ZoneType.Need need) {
        int[] n = {0};
        near(b, radius, o -> { if (o.id != b.id && o.zone.need == need) n[0]++; });
        return n[0];
    }

    /** Working buildings of a kind in the whole town. */
    public int working(com.cloudkingtv.townstead.zone.ZoneType.Need need) {
        return working.getOrDefault(need, 0);
    }

    private void near(Building b, double radius, java.util.function.Consumer<Building> each) {
        double x = b.centerX(), z = b.centerZ();
        int x0 = Math.floorDiv((int) Math.floor(x - radius), NEAR_CELL), x1 = Math.floorDiv((int) Math.floor(x + radius), NEAR_CELL);
        int z0 = Math.floorDiv((int) Math.floor(z - radius), NEAR_CELL), z1 = Math.floorDiv((int) Math.floor(z + radius), NEAR_CELL);
        for (int gx = x0; gx <= x1; gx++) for (int gz = z0; gz <= z1; gz++) {
            for (Building o : near.getOrDefault(((long) gx << 32) | (gz & 0xFFFFFFFFL), List.of())) {
                if (o.distanceTo(b) <= radius) each.accept(o);
            }
        }
    }

    /** Average education (0-3) of a home's residents. */
    public double education(Building home) {
        boolean school = schooled.contains(home.id);
        int n = 0, sum = 0;
        for (Residents.Household h : Residents.households(home)) {
            for (Residents.Person p : h.members()) { n++; sum += p.education(school); }
        }
        return n == 0 ? 0 : (double) sum / n;
    }

    /** Buildings that are lived in or worked in (for info views). */
    public List<Building> working() {
        List<Building> out = new ArrayList<>();
        for (Building b : town) if (b.working()) out.add(b);
        return out;
    }
}
