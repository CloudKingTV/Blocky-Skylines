package com.cloudkingtv.townstead.sim;

import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.Demand;
import com.cloudkingtv.townstead.economy.Budget;
import com.cloudkingtv.townstead.service.ServiceType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/**
 * How content a home's residents or a workplace's staff are, 0-100: 50 plus a list of plain factors ("+8 a park in
 * reach", "-6 taxes at 12%"), each shown in the building panel. Levels ask for it (50 for level 2, 65 for level 3)
 * and below {@link #UNHAPPY} it's trouble that ends in abandonment.
 */
public record Happiness(int total, List<Factor> factors) {
    public record Factor(Component label, int delta) {}

    public static final int BASE = 50, UNHAPPY = 20, LEVEL_2 = 50, LEVEL_3 = 65;

    static Happiness of(Building b, TownModel m) {
        List<Factor> f = new ArrayList<>();
        if (b.civic()) return new Happiness(BASE, f);
        boolean home = b.zone.residential();
        // Utilities: a little nicer with them; built-for-them levels miss them badly.
        int missing = b.level >= 2 ? -15 : -5;
        f.add(m.util.power(b) ? factor("power", 5) : factor("no_power", missing));
        f.add(m.util.water(b) ? factor("water", 5) : factor("no_water", missing));
        List<ServiceType> wanted = home ? List.of(ServiceType.CLINIC, ServiceType.SCHOOL, ServiceType.GUARD_HOUSE, ServiceType.FIRE_STATION, ServiceType.PARK)
                : List.of(ServiceType.GUARD_HOUSE, ServiceType.FIRE_STATION, ServiceType.COMPOST_YARD);
        for (ServiceType s : wanted) {
            if (m.covered(b, s)) f.add(new Factor(Component.translatable("happy.townstead.service", s.displayName()), s == ServiceType.PARK ? 8 : 5));
        }
        int lv = m.fields.landValue(b), pollution = m.fields.pollution(b), noise = m.fields.noise(b);
        if (home || b.zone.need == com.cloudkingtv.townstead.zone.ZoneType.Need.OFFICES) {
            if (pollution > 0) f.add(new Factor(Component.translatable("happy.townstead.pollution", pollution), -Math.round(pollution / 3f)));
            if (noise >= 5) f.add(new Factor(Component.translatable("happy.townstead.noise", noise), -Math.round(noise / 5f)));
        }
        if (home) f.add(new Factor(Component.translatable("happy.townstead.land_value", lv), Math.round((lv - 50) / 5f)));
        int kind = taxKind(b);
        int tax = m.budget.tax(kind);
        if (tax != Demand.NEUTRAL_TAX) f.add(new Factor(Component.translatable("happy.townstead.taxes", tax), (Demand.NEUTRAL_TAX - tax) * 2));
        if (home) {
            int u = m.unemploymentRate();
            if (u > 10) f.add(new Factor(Component.translatable("happy.townstead.unemployment", u), -(u - 10) / 2));
        } else {
            int staff = m.staff(b);
            if (b.jobs > 0 && staff < b.jobs) f.add(new Factor(Component.translatable("happy.townstead.short_staffed", staff, b.jobs),
                    -Math.round(20f * (b.jobs - staff) / b.jobs)));
            if (b.zone.need == com.cloudkingtv.townstead.zone.ZoneType.Need.SHOPS) {
                boolean busy = m.residentsNear(b, 32) >= 10;
                f.add(factor(busy ? "customers" : "few_customers", busy ? 5 : -5));
            }
        }
        if (m.broke()) f.add(factor("broke", -5));
        f.removeIf(x -> x.delta() == 0);
        int total = BASE;
        for (Factor x : f) total += x.delta();
        return new Happiness(Mth.clamp(total, 0, 100), f);
    }

    /** Which tax a building pays: {@link Budget#HOMES} and so on. */
    public static int taxKind(Building b) {
        return switch (b.zone.need) {
            case HOMES -> Budget.HOMES;
            case SHOPS -> Budget.SHOPS;
            case WORKSHOPS -> Budget.WORKSHOPS;
            case OFFICES, CIVIC -> Budget.OFFICES;
        };
    }

    private static Factor factor(String key, int delta) {
        return new Factor(Component.translatable("happy.townstead." + key), delta);
    }

    /** The factor dragging happiness down most, or null if none does. */
    public @Nullable Factor worst() {
        Factor worst = null;
        for (Factor x : factors) if (x.delta() < 0 && (worst == null || x.delta() < worst.delta())) worst = x;
        return worst;
    }
}
