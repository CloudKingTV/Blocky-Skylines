package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.BuildingGenerator;
import com.cloudkingtv.townstead.building.Progression;
import com.cloudkingtv.townstead.building.Residents;
import com.cloudkingtv.townstead.client.ClientLand;
import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.service.Utilities;
import com.cloudkingtv.townstead.economy.Budget;
import com.cloudkingtv.townstead.economy.Finance;
import com.cloudkingtv.townstead.sim.Happiness;
import com.cloudkingtv.townstead.sim.TownModel;
import java.util.ArrayList;
import java.util.List;
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
        TownModel m = PlannerClient.model();
        if (m == null) return out;
        BuildingGenerator.Plan plan = BuildingGenerator.plan(b);
        long now = Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime();
        double speed = TownsteadConfig.GROWTH_SPEED.get();
        Utilities.State util = m.util;

        out.add(new Line(name(b), TITLE, 0));
        if (b.civic()) {
            ServiceType s = b.serviceType();
            out.add(new Line(Component.translatable("panel.townstead.civic", s.displayName(), Component.translatable("style.townstead." + b.style)),
                    0xFF000000 | b.zone.rgb, 0));
            int strength = (int) Math.round(m.strength(s) * 100);
            out.add(new Line(switch (s.kind) {
                case POWER -> Component.translatable("panel.townstead.supplies_power", Math.round(s.capacity * m.strength(s)));
                case WATER -> Component.translatable("panel.townstead.supplies_water", Math.round(s.capacity * m.strength(s)));
                case SERVICE -> Component.translatable("panel.townstead.covers", Math.round(m.radius(s)));
                case TRANSIT -> Component.translatable("panel.townstead.coaches", m.traffic.lines().values().stream().mapToInt(l -> l.coaches()).sum(),
                        m.lines.size(), m.traffic.riders());
            }, TEXT, 0));
            if (s.monument()) out.add(new Line(Component.translatable("service.townstead.monument_tip." + s.key()), GOOD, 0));
            out.add(new Line(Component.translatable("panel.townstead.upkeep", Budget.format((long) s.upkeep * m.budget.budget(s.department) / 100),
                    s.department.displayName(), m.budget.budget(s.department)), strength < 100 ? WARN : DIM, 0));
            if (m.broke()) out.add(new Line(Component.translatable("panel.townstead.half_strength"), BAD, 0));
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
            out.add(new Line(Component.translatable("panel.townstead.lot", m.fields.landValue(b), m.fields.pollution(b), m.fields.noise(b)), DIM, 0));
            if (b.working()) {
                int kind = Happiness.taxKind(b);
                long tax = Math.round(Finance.value(b, m) * m.budget.tax(kind) / 100.0);
                out.add(new Line(Component.translatable("panel.townstead.pays", Budget.format(tax), m.budget.tax(kind)), DIM, 0));
            }
        }

        // Status.
        switch (b.state) {
            case CONSTRUCTING -> out.add(new Line(Component.translatable(b.repairing ? "panel.townstead.repairing"
                    : b.upgrading ? "panel.townstead.upgrading" : "building.townstead.progress",
                    Math.round(100f * b.progress / plan.steps().size())), WARN, 0));
            case PAUSED -> out.add(new Line(Component.translatable("building.townstead.waiting",
                    b.needs == null ? Component.empty() : b.needs.displayName()), BAD, 0));
            case ABANDONED -> out.add(new Line(Component.translatable(b.burned ? "panel.townstead.burned" : "panel.townstead.abandoned"), BAD, 0));
            case ACTIVE -> out.add(new Line(b.zone.residential()
                    ? Component.translatable("building.townstead.residents", Residents.people(b))
                    : Component.translatable("panel.townstead.jobs_open", b.jobs), GOOD, 0));
        }
        if (b.fire > 0) {
            boolean brigade = m.covered(b, com.cloudkingtv.townstead.service.ServiceType.FIRE_STATION);
            out.add(new Line(Component.translatable("panel.townstead.burning"), BAD, 0));
            out.add(new Line(Component.translatable(brigade ? "panel.townstead.brigade" : "panel.townstead.no_brigade"), brigade ? WARN : BAD, 4));
        }
        if (b.trouble > 0 || b.state == Building.State.ABANDONED) {
            Component why = Progression.trouble(b, m);
            if (why != null) {
                int left = (int) Math.max(1, Math.ceil((Progression.ABANDON_AFTER - b.trouble) / speed / 1200));
                out.add(new Line(b.state == Building.State.ABANDONED ? Component.translatable("panel.townstead.because", why)
                        : Component.translatable("panel.townstead.trouble", why, left), BAD, 0));
            } else if (b.state == Building.State.ABANDONED) {
                out.add(new Line(Component.translatable("panel.townstead.recovering"), GOOD, 0));
            }
        }

        // Happiness, factor by factor.
        if (!b.civic() && b.working()) {
            Happiness h = m.happiness(b);
            out.add(new Line(Component.empty(), TEXT, 0));
            out.add(new Line(Component.translatable("panel.townstead.happiness", h.total()),
                    h.total() >= Happiness.LEVEL_3 ? GOOD : h.total() >= Happiness.LEVEL_2 ? TITLE : h.total() >= Happiness.UNHAPPY ? WARN : BAD, 0));
            for (Happiness.Factor f : h.factors()) {
                out.add(new Line(Component.literal((f.delta() > 0 ? "+" : "") + f.delta() + "  ").append(f.label()), f.delta() > 0 ? GOOD : BAD, 4));
            }
        }

        // Next level.
        if (b.civic()) {
            // No levels for services; fall through to staff.
        } else if (b.level < Building.MAX_LEVEL) {
            out.add(new Line(Component.empty(), TEXT, 0));
            out.add(new Line(Component.translatable("panel.townstead.next_level", b.level + 1), TITLE, 0));
            for (Progression.Factor f : Progression.nextLevel(b, m, now, speed)) {
                out.add(new Line(Component.literal(f.met() ? "✓ " : "✗ ").append(f.label()), f.met() ? GOOD : BAD, 4));
            }
        } else {
            out.add(new Line(Component.translatable("panel.townstead.top_level"), GOOD, 0));
        }

        out.add(new Line(Component.empty(), TEXT, 0));
        if (b.zone.residential()) {
            List<Residents.Household> households = b.working() ? Residents.households(b) : List.of();
            boolean school = m.schooled.contains(b.id);
            out.add(new Line(Component.translatable("panel.townstead.households", households.size()), TITLE, 0));
            if (school) out.add(new Line(Component.translatable("panel.townstead.schooled"), GOOD, 2));
            for (Residents.Household h : households) {
                out.add(new Line(Component.translatable("panel.townstead.family", h.surname()), TEXT, 2));
                for (Residents.Person p : h.members()) {
                    Residents.Job job = m.jobsByPerson.get(b.id + ":" + p.name());
                    Component what = switch (p.stage()) {
                        case CHILD -> Component.translatable("panel.townstead.child");
                        case TEEN -> Component.translatable("panel.townstead.teen");
                        case SENIOR -> Component.translatable("panel.townstead.retired");
                        case ADULT -> job == null ? Component.translatable("panel.townstead.unemployed")
                                : Component.translatable("panel.townstead.works_at", Component.translatable("job.townstead." + job.trade()),
                                        workplaceName(job.workplaceId()));
                    };
                    out.add(new Line(Component.translatable("panel.townstead.person", p.name(), p.age(), what,
                                    Component.translatable("education.townstead." + p.education(school))),
                            p.stage() == Residents.Stage.ADULT && job == null ? WARN : DIM, 8));
                }
            }
        } else {
            List<Residents.Job> staff = m.employment.getOrDefault(b.id, List.of());
            out.add(new Line(Component.translatable("panel.townstead.workers", staff.size(), b.jobs), TITLE, 0));
            for (Residents.Job j : staff) {
                out.add(new Line(Component.translatable("panel.townstead.worker", j.person().name(),
                        Component.translatable("job.townstead." + j.trade()), homeName(j.homeId())), DIM, 4));
            }
            if (staff.size() < b.jobs && b.working()) {
                boolean skilled = Residents.required(b, staff.size()) >= Residents.SKILLED;
                out.add(new Line(Component.translatable(skilled ? "panel.townstead.vacancies_skilled" : "panel.townstead.vacancies",
                        b.jobs - staff.size()), WARN, 4));
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
