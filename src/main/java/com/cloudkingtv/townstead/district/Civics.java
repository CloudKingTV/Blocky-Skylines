package com.cloudkingtv.townstead.district;

import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.transit.CoachLine;
import com.cloudkingtv.townstead.zone.ZoneRules;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The mayor's standing decisions for a town, as the model reads them: coach lines, districts with their policies and
 * specialisations, and town-wide policies. A building is under a policy if the whole town has it or its district does
 * (its district is the one holding its front-left cell).
 */
public record Civics(List<CoachLine> lines, List<District> districts, Set<Policy> policies) {
    public static final Civics NONE = new Civics(List.of(), List.of(), Set.of());

    public Civics {
        lines = List.copyOf(lines);
        districts = List.copyOf(districts);
        policies = policies.isEmpty() ? EnumSet.noneOf(Policy.class) : EnumSet.copyOf(policies);
    }

    /** District by cell, built once per Civics. */
    private Map<Long, District> index() {
        Map<Long, District> map = INDEX.get(this);
        if (map != null) return map;
        map = new HashMap<>();
        for (District d : districts) for (long c : d.cells()) map.put(c, d);
        if (INDEX.size() > 64) INDEX.clear();
        INDEX.put(this, map);
        return map;
    }

    private static final Map<Civics, Map<Long, District>> INDEX = java.util.Collections.synchronizedMap(new java.util.IdentityHashMap<>());

    public @Nullable District district(long cell) { return index().get(cell); }

    public @Nullable District district(Building b) { return district(ZoneRules.cellKey(b.cellX, b.cellZ)); }

    public boolean has(long cell, Policy p) {
        if (policies.contains(p)) return true;
        District d = district(cell);
        return d != null && d.policies().contains(p);
    }

    public boolean has(Building b, Policy p) { return has(ZoneRules.cellKey(b.cellX, b.cellZ), p); }

    /** Whether the whole town or any district has it (for town-level effects like demand). */
    public boolean anywhere(Policy p) {
        if (policies.contains(p)) return true;
        for (District d : districts) if (d.policies().contains(p)) return true;
        return false;
    }

    public Specialization spec(long cell) {
        District d = district(cell);
        return d == null ? Specialization.NONE : d.spec();
    }
}
