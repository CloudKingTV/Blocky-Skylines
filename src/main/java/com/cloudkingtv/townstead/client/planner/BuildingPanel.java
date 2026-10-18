package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.BuildingGenerator;
import com.cloudkingtv.townstead.building.Demand;
import com.cloudkingtv.townstead.building.Progression;
import com.cloudkingtv.townstead.building.Residents;
import com.cloudkingtv.townstead.client.ClientLand;
import com.cloudkingtv.townstead.client.ClientRoads;
import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.service.Utilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The building panel: who lives or works in a building, its level and what the next level needs, and what's
 * wrong if anything is. Built from the same shared rules the server uses, so it explains real decisions.
 */
public final class BuildingPanel {
    public record Line(Component text, int color, int indent) {}

    private static final int TITLE = 0xFFFFE9B0, TEXT = 0xFFDDDDDD, DIM = 0xFF9A9A9A, GOOD = 0xFF9EE37D, BAD = 0xFFFF8A7A, WARN = 0xFFFFD36B;

    /** "Miller Cottage" for homes, "Thatcher's Smithy" for workplaces. */
    public static Component name(Building b) {
        BuildingGenerator.Plan plan = BuildingGenerator.plan(b);
        if (b.zone.residential()) {
            List<Residents.Household> hh = Residents.households(b);
            String family = hh.isEmpty() ? Residents.ownerName(b) : hh.getFirst().surname();
            return Component.translatable("building.townstead.home_name", family, Component.translatable("building.townstead.kind." + plan.kind()));
        }
        if (b.civic()) return Component.translatable("building.townstead.civic_name", b.serviceType().displayName());
        return Component.translatable("building.townstead.place_name", Residents.ownerName(b),
                Component.translatable("building.townstead.place." + Residents.placeKind(b)));
    }

    public static List<Line> lines(Building b) {
        List<Line> out = new ArrayList<>();
        List<Building> town = ClientLand.get().buildingsOf(b.city);
        BuildingGenerator.Plan plan = BuildingGenerator.plan(b);
        long now = Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
        double speed = TownsteadConfig.GROWTH_SPEED.get();
        Utilities.State util = Utilities.compute(town, ClientRoads.network());

        out.add(new Line(name(b), TITLE, 0));
        if (b.civic()) {
            ServiceType s = b.serviceType();
            out.add(new Line(Component.translatable("panel.townstead.civic", s.displayName(), Component.translatable("style.townstead." + b.style)),
                    0xFF000000 | b.zone.rgb, 0));
            out.add(new Line(switch (s.kind) {
                case POWER -> Component.translatable("panel.townstead.supplies_power", s.capacity);
                case WATER -> Component.translatable("panel.townstead.supplies_water", s.capacity);
                case SERVICE -> Component.translatable("panel.townstead.covers", s.radius);
            }, TEXT, 0));
            Utilities.Grid grid = util.grid(b);
            if (grid != null && s.kind != ServiceType.Kind.SERVICE) {
                out.add(new Line(Component.translatable("panel.townstead.grid", grid.powerUsed(), grid.powerCapacity(), grid.waterUsed(), grid.waterCapacity()), DIM, 0));
            }
        } else {
            out.add(new Line(Component.translatable("panel.townstead.zone_level", b.zone.displayName(), b.level, Building.MAX_LEVEL,
                    Component.translatable("style.townstead." + b.style)), 0xFF000000 | b.zone.rgb, 0));
            out.add(new Line(Component.translatable("panel.townstead.utilities",
                    Component.translatable(util.power(b) ? "panel.townstead.yes" : "panel.townstead.no"),
                    Component.translatable(util.water(b) ? "panel.townstead.yes" : "panel.townstead.no")),
                    util.power(b) && util.water(b) ? GOOD : WARN, 0));
        }

        // Status.
        switch (b.state) {
            case CONSTRUCTING -> out.add(new Line(Component.translatable(b.repairing ? "panel.townstead.repairing"
                    : b.upgrading ? "panel.townstead.upgrading" : "building.townstead.progress",
                    Math.round(100f * b.progress / plan.steps().size())), WARN, 0));
            case PAUSED -> out.add(new Line(Component.translatable("building.townstead.waiting",
                    b.needs == null ? Component.empty() : b.needs.displayName()), BAD, 0));
            case ABANDONED -> out.add(new Line(Component.translatable("panel.townstead.abandoned"), BAD, 0));
            case ACTIVE -> out.add(new Line(b.zone.residential()
                    ? Component.translatable("building.townstead.residents", Residents.people(b))
                    : Component.translatable("panel.townstead.jobs_open", b.jobs), GOOD, 0));
        }
        if (b.trouble > 0 || b.state == Building.State.ABANDONED) {
            var city = PlannerClient.city().orElse(null);
            Component why = city == null ? null : Progression.trouble(b, town, ClientRoads.network(), Demand.of(town), java.util.Set.copyOf(city.tiles()), util);
            if (why != null) {
                int left = (int) Math.max(1, Math.ceil((Progression.ABANDON_AFTER - b.trouble) / speed / 1200));
                out.add(new Line(b.state == Building.State.ABANDONED ? Component.translatable("panel.townstead.because", why)
                        : Component.translatable("panel.townstead.trouble", why, left), BAD, 0));
            } else if (b.state == Building.State.ABANDONED) {
                out.add(new Line(Component.translatable("panel.townstead.recovering"), GOOD, 0));
            }
        }

        // Next level.
        if (b.civic()) {
            // No levels for services; fall through to staff.
        } else if (b.level < Building.MAX_LEVEL) {
            out.add(new Line(Component.empty(), TEXT, 0));
            out.add(new Line(Component.translatable("panel.townstead.next_level", b.level + 1), TITLE, 0));
            for (Progression.Factor f : Progression.nextLevel(b, town, ClientRoads.network(), now, speed, util)) {
                out.add(new Line(Component.literal(f.met() ? "✓ " : "✗ ").append(f.label()), f.met() ? GOOD : BAD, 4));
            }
        } else {
            out.add(new Line(Component.translatable("panel.townstead.top_level"), GOOD, 0));
        }

        Map<Integer, List<Residents.Job>> employment = Residents.employment(town);
        out.add(new Line(Component.empty(), TEXT, 0));
        if (b.zone.residential()) {
            Map<String, Residents.Job> jobs = Residents.jobsByPerson(employment);
            List<Residents.Household> households = b.working() ? Residents.households(b) : List.of();
            out.add(new Line(Component.translatable("panel.townstead.households", households.size()), TITLE, 0));
            for (Residents.Household h : households) {
                out.add(new Line(Component.translatable("panel.townstead.family", h.surname()), TEXT, 2));
                for (Residents.Person p : h.members()) {
                    Residents.Job job = jobs.get(b.id + ":" + p.name());
                    Component what = switch (p.stage()) {
                        case CHILD -> Component.translatable("panel.townstead.child");
                        case TEEN -> Component.translatable("panel.townstead.teen");
                        case SENIOR -> Component.translatable("panel.townstead.retired");
                        case ADULT -> job == null ? Component.translatable("panel.townstead.unemployed")
                                : Component.translatable("panel.townstead.works_at", Component.translatable("job.townstead." + job.trade()),
                                        workplaceName(job.workplaceId()));
                    };
                    out.add(new Line(Component.translatable("panel.townstead.person", p.name(), p.age(), what),
                            p.stage() == Residents.Stage.ADULT && job == null ? WARN : DIM, 8));
                }
            }
        } else {
            List<Residents.Job> staff = employment.getOrDefault(b.id, List.of());
            out.add(new Line(Component.translatable("panel.townstead.workers", staff.size(), b.jobs), TITLE, 0));
            for (Residents.Job j : staff) {
                out.add(new Line(Component.translatable("panel.townstead.worker", j.person().name(),
                        Component.translatable("job.townstead." + j.trade()), homeName(j.homeId())), DIM, 4));
            }
            if (staff.size() < b.jobs && b.working()) {
                out.add(new Line(Component.translatable("panel.townstead.vacancies", b.jobs - staff.size()), WARN, 4));
            }
        }
        return out;
    }

    private static Component workplaceName(int id) {
        Building w = ClientLand.get().building(id);
        return w == null ? Component.literal("?") : name(w);
    }

    private static Component homeName(int id) {
        @Nullable Building h = ClientLand.get().building(id);
        return h == null ? Component.literal("?") : name(h);
    }

    private BuildingPanel() {}
}
