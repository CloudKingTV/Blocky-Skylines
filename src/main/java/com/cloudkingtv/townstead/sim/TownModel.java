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

    private TownModel(UUID city, Collection<Building> town, RoadNetwork roads, Budget budget, long treasury, Set<TilePos> tiles, BlockPos townHall,
                      List<com.cloudkingtv.townstead.transit.CoachLine> lines) {
        this.city = city;
        this.town = List.copyOf(town);
        this.roads = roads;
        this.budget = budget;
        this.treasury = treasury;
        this.tiles = tiles;
        this.lines = List.copyOf(lines);
        this.util = Utilities.compute(this.town, roads, this::strength);
        for (Building b : this.town) if (b.zone.residential() && covered(b, ServiceType.SCHOOL)) schooled.add(b.id);
        this.employment = Residents.employment(this.town, schooled);
        this.jobsByPerson = Residents.jobsByPerson(employment);
        this.traffic = new Traffic(this, this.lines);
        this.fields = Fields.of(city, this.town, roads, townHall, this::radius, traffic);
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
        this.demand = Demand.of(this.town, e, budget.taxes);
    }

    public static TownModel of(UUID city, Collection<Building> town, RoadNetwork roads, Budget budget, long treasury,
                               Collection<TilePos> tiles, BlockPos townHall, List<com.cloudkingtv.townstead.transit.CoachLine> lines) {
        return new TownModel(city, town, roads, budget, treasury, Set.copyOf(tiles), townHall, lines);
    }

    /** In debt: services and utilities run at half strength until the treasury is back above zero. */
    public boolean broke() {
        return treasury < 0;
    }

    /** How hard a department works: its budget (1.0 at 100%), halved while the town is broke. */
    public double strength(ServiceType s) {
        return budget.budget(s.department) / 100.0 * (broke() ? 0.5 : 1.0);
    }

    /** A service's reach in blocks: 80% at a 50% budget, 120% at 150%. */
    public double radius(ServiceType s) {
        return s.radius * (0.6 + 0.4 * strength(s));
    }

    public boolean covered(Building b, ServiceType s) {
        return Utilities.covered(b, town, s, radius(s));
    }

    public Happiness happiness(Building b) {
        return happiness.computeIfAbsent(b.id, k -> Happiness.of(b, this));
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
        int n = 0;
        for (Building o : town) {
            if (!o.zone.residential() || !o.working()) continue;
            if (o.distanceTo(b) <= radius) n += Residents.people(o);
        }
        return n;
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
