package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.client.ClientLand;
import com.cloudkingtv.townstead.client.ClientRoads;
import com.cloudkingtv.townstead.economy.Finance;
import com.cloudkingtv.townstead.progress.Milestone;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.sim.Happiness;
import com.cloudkingtv.townstead.sim.TownModel;
import com.cloudkingtv.townstead.zone.LandData;
import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * The town advisor: a card in the corner of the planner with the most useful next step for the town as it stands
 * (no roads yet, homes without power, losing money, jammed roads...), naming the tool and key to use. Tips are
 * worked out afresh from the town every frame, so they follow what the player does. Until the town is a Worthy
 * Village the first card is a checklist of first steps, ticked off as they're done. "Next" shows the other tips
 * that apply; "Dismiss" puts a tip away for good (kept in the client settings); the cross in the corner hides the
 * advisor until it's turned back on with the ? key.
 */
final class Advisor {
    private static final Identifier PANEL = Townstead.id("planner/panel");
    private static final int W = 196, PAD = 6;
    /** Which of the tips that apply is shown (Next cycles). */
    private static int index;
    private static int left, top, h;
    private static int nextX0, nextX1, dismissX0, rowY, closeX0, closeY;
    private static @Nullable String shownKey;

    record Tip(String key, Object[] args, List<Step> steps) {
        Tip(String key, Object[] args) { this(key, args, List.of()); }
        Component title() { return Component.translatable("advisor.townstead." + key + ".title", args); }
        Component body() { return Component.translatable("advisor.townstead." + key, args); }
    }

    /** One of the first steps, and whether the town has done it. */
    record Step(String key, boolean done) {}

    /** The tips the first steps already cover, left out while they're shown. */
    private static final java.util.Set<String> IN_STEPS = java.util.Set.of("roads", "zone_homes", "utilities", "jobs");

    static boolean shown() {
        return TownsteadConfig.SHOW_ADVISOR.get();
    }

    static void toggle() {
        TownsteadConfig.SHOW_ADVISOR.set(!shown());
        TownsteadConfig.SHOW_ADVISOR.save();
        index = 0;
    }

    /** True if this player builds without materials (creative, or the server's free-build setting). */
    static boolean freeBuild() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        return TownsteadConfig.FREE_BUILD.get() || player != null && player.getAbilities().instabuild;
    }

    /**
     * The first steps for a new town, in order, each ticked off from the town as it stands: stock the storehouse (in
     * survival: roads and houses are built from it), lay a road, zone homes, power and water, shops and work, then
     * grow to Worthy Village. Empty once the town is a Worthy Village, or if the player hid them.
     */
    static List<Step> firstSteps(CitySummary city) {
        if (!TownsteadConfig.FIRST_STEPS.get() || city.milestone() > 0) return List.of();
        LandData land = ClientLand.get();
        List<Building> town = land.buildingsOf(city.id());
        boolean roads = ClientRoads.network().segments().stream().anyMatch(s -> s.city().equals(city.id()));
        boolean homes = false, jobs = false;
        for (var z : land.zones().values()) {
            if (!z.city().equals(city.id())) continue;
            if (z.type().residential()) homes = true;
            else if (z.type() != ZoneType.CIVIC) jobs = true;
        }
        for (Building b : town) {
            if (b.zone.residential()) homes = true;
            else if (!b.civic()) jobs = true;
        }
        boolean power = town.stream().anyMatch(b -> b.civic() && b.serviceType().kind == ServiceType.Kind.POWER);
        boolean water = town.stream().anyMatch(b -> b.civic() && b.serviceType().kind == ServiceType.Kind.WATER);
        int stocked = 0;
        for (int n : city.stock().values()) stocked += n;
        List<Step> out = new ArrayList<>();
        if (!freeBuild()) out.add(new Step("stock", stocked >= 32 || roads));
        out.add(new Step("road", roads));
        out.add(new Step("homes", homes));
        out.add(new Step("utilities", power && water));
        out.add(new Step("jobs", jobs));
        out.add(new Step("grow", false));
        return out;
    }

    static boolean dismissed(String key) {
        return TownsteadConfig.DISMISSED_TIPS.get().contains(key);
    }

    static void dismiss(String key) {
        if (key.equals("steps")) {
            TownsteadConfig.FIRST_STEPS.set(false);
            TownsteadConfig.FIRST_STEPS.save();
        } else if (!dismissed(key)) {
            List<String> list = new ArrayList<>(TownsteadConfig.DISMISSED_TIPS.get());
            list.add(key);
            TownsteadConfig.DISMISSED_TIPS.set(list);
            TownsteadConfig.DISMISSED_TIPS.save();
        }
    }

    /** Every tip that applies to the town now, most pressing first, without the ones the player dismissed. */
    static List<Tip> tips(CitySummary city, @Nullable TownModel m) {
        List<Tip> all = allTips(city, m);
        List<Step> steps = firstSteps(city);
        List<Tip> out = new ArrayList<>();
        if (!steps.isEmpty()) {
            Step now = steps.stream().filter(st -> !st.done()).findFirst().orElse(steps.getLast());
            int people = m == null ? 0 : m.demand.population();
            Milestone next = Milestone.of(city.milestone()).next();
            int goal = next == null ? 0 : next.population;
            out.add(new Tip("steps", new Object[] {steps.indexOf(now) + 1, steps.size(), now.key(), people, goal}, steps));
        }
        for (Tip t : all) {
            if (!steps.isEmpty() && IN_STEPS.contains(t.key())) continue;
            if (!dismissed(t.key())) out.add(t);
        }
        return out;
    }

    /** What the current first step says to do. */
    static Component stepBody(Tip tip) {
        String key = (String) tip.args()[2];
        return Component.translatable("advisor.townstead.step." + key + ".body", tip.args()[3], tip.args()[4]);
    }

    private static List<Tip> allTips(CitySummary city, @Nullable TownModel m) {
        List<Tip> out = new ArrayList<>();
        LandData land = ClientLand.get();
        List<Building> town = land.buildingsOf(city.id());
        boolean roads = ClientRoads.network().segments().stream().anyMatch(s -> s.city().equals(city.id()));
        int homesZoned = 0, jobsZoned = 0;
        for (var z : land.zones().values()) {
            if (!z.city().equals(city.id())) continue;
            if (z.type().residential()) homesZoned++;
            else if (z.type() != ZoneType.CIVIC) jobsZoned++;
        }
        for (Building b : town) {
            if (b.zone.residential()) homesZoned++;
            else if (!b.civic()) jobsZoned++;
        }
        if (!roads) { out.add(new Tip("roads", new Object[0])); return out; }
        if (homesZoned == 0) { out.add(new Tip("zone_homes", new Object[0])); return out; }
        boolean hasPower = town.stream().anyMatch(b -> b.civic() && b.serviceType().kind == ServiceType.Kind.POWER);
        boolean hasWater = town.stream().anyMatch(b -> b.civic() && b.serviceType().kind == ServiceType.Kind.WATER);
        if (!hasPower || !hasWater) out.add(new Tip("utilities", new Object[0]));
        if (jobsZoned == 0) out.add(new Tip("jobs", new Object[0]));
        if (m != null) {
            int noPower = 0, noWater = 0, unhappy = 0, waiting = 0;
            for (Building b : town) {
                if (b.state == Building.State.PAUSED && b.needs != null) waiting++;
                if (b.civic() || !b.working()) continue;
                if (!m.util.power(b)) noPower++;
                if (!m.util.water(b)) noWater++;
                if (m.happiness(b).total() < Happiness.UNHAPPY) unhappy++;
            }
            if (hasPower && hasWater && (noPower > 0 || noWater > 0)) out.add(new Tip("more_utilities", new Object[] {noPower, noWater}));
            if (waiting > 0) out.add(new Tip("waiting", new Object[] {waiting}));
            // Sites waiting on something the town doesn't make; workshops with nothing to work.
            var day = m.industry();
            for (Building b : town) {
                if (b.state != Building.State.PAUSED || b.needs == null || day.made(b.needs) > 0) continue;
                var makers = com.cloudkingtv.townstead.industry.Industry.makers(b.needs);
                out.add(new Tip(makers.isEmpty() ? "supply_buy" : "supply", new Object[] {b.needs.displayName(),
                        makers.isEmpty() ? Component.empty() : Component.translatable("trade.townstead." + makers.getFirst().key() + ".how")}));
                break;
            }
            for (var f : day.flows()) {
                if (f.shortOf() == null) continue;
                long n = day.flows().stream().filter(o -> o.shortOf() != null).count();
                var makers = com.cloudkingtv.townstead.industry.Industry.makers(f.shortOf());
                out.add(new Tip("starved", new Object[] {n, f.shortOf().displayName(),
                        makers.isEmpty() ? Component.empty() : Component.translatable("trade.townstead." + makers.getFirst().key() + ".how")}));
                break;
            }
            if (Finance.daily(m).net() < 0) out.add(new Tip("money", new Object[0]));
            if (unhappy >= 3) out.add(new Tip("unhappy", new Object[] {unhappy}));
            if (m.traffic.trips() > 60 && m.traffic.flowPercent() < 60) out.add(new Tip("traffic", new Object[] {m.traffic.flowPercent()}));
            // Services the homes lack that the town could build.
            for (ServiceType s : List.of(ServiceType.CLINIC, ServiceType.SCHOOL, ServiceType.FIRE_STATION, ServiceType.GUARD_HOUSE, ServiceType.PARK)) {
                if (!Milestone.unlocked(city.milestone(), s)) continue;
                long without = town.stream().filter(b -> b.zone.residential() && b.working() && !m.covered(b, s)).count();
                if (without >= 4) { out.add(new Tip("service", new Object[] {s.displayName(), without})); break; }
            }
            var d = m.demand;
            if (d.residential() >= 60) out.add(new Tip("demand", new Object[] {ZoneType.RESIDENTIAL.displayName()}));
            else if (d.commercial() >= 60) out.add(new Tip("demand", new Object[] {ZoneType.COMMERCIAL.displayName()}));
            else if (d.industrial() >= 60) out.add(new Tip("demand", new Object[] {ZoneType.INDUSTRIAL.displayName()}));
        }
        Milestone next = Milestone.of(city.milestone()).next();
        if (next != null) {
            out.add(new Tip("milestone", new Object[] {next.displayName(), next.population}));
        }
        out.add(new Tip("good_town", new Object[0]));
        return out;
    }

    static boolean contains(double x, double y) {
        return shown() && h > 0 && x >= left && x < left + W && y >= top && y < top + h;
    }

    /** Next / dismiss / close; true if the click was the advisor's. */
    static boolean click(double x, double y) {
        if (!contains(x, y)) return false;
        if (y >= closeY && y < closeY + 10 && x >= closeX0) toggle();
        else if (y >= rowY && y < rowY + 10) {
            if (x >= nextX0 && x < nextX1) index++;
            else if (x >= dismissX0 && shownKey != null) dismiss(shownKey);
        }
        return true;
    }

    /** Draws the card with its bottom edge at {@code bottom}. */
    static void draw(GuiGraphicsExtractor g, Font font, int bottom, int mouseX, int mouseY) {
        draw(g, font, bottom, 0, mouseX, mouseY);
    }

    /** As above, keeping clear of the {@code room} pixels at the top of the screen (bars and the cell card). */
    static void draw(GuiGraphicsExtractor g, Font font, int bottom, int room, int mouseX, int mouseY) {
        h = 0;
        shownKey = null;
        if (!shown()) return;
        CitySummary city = PlannerClient.city().orElse(null);
        if (city == null) return;
        List<Tip> tips = tips(city, PlannerClient.model());
        if (tips.isEmpty()) return;
        Tip tip = tips.get(Math.floorMod(index, tips.size()));
        shownKey = tip.key();
        boolean steps = tip.key().equals("steps");
        List<FormattedCharSequence> body = font.split(steps ? stepBody(tip) : tip.body(), W - PAD * 2);
        int stepLines = steps ? tip.steps().size() : 0;
        h = PAD * 2 + 11 + stepLines * 10 + (steps ? 3 : 0) + body.size() * (font.lineHeight + 1) + 13;
        // Short of room (a small window, a big GUI scale): the checklist folds down to the step in hand.
        boolean compact = steps && h > bottom - room;
        if (compact) {
            stepLines = 1;
            h = PAD * 2 + 11 + 10 + 3 + body.size() * (font.lineHeight + 1) + 13;
        }
        left = 6;
        top = bottom - h;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left, top, W, h);
        int y = top + PAD;
        Component title = steps ? Component.translatable("advisor.townstead.steps.title", tip.args()[0], tip.args()[1]) : tip.title();
        g.text(font, Component.literal("» ").append(title), left + PAD, y, 0xFFFFE9B0, true);
        closeY = y;
        closeX0 = left + W - PAD - font.width("×") - 2;
        boolean hotClose = mouseX >= closeX0 && mouseX < left + W && mouseY >= closeY && mouseY < closeY + 10;
        g.text(font, "×", closeX0, closeY, hotClose ? 0xFFFFFFFF : 0xFF9A9A9A, true);
        if (hotClose) g.setTooltipForNextFrame(font, Component.translatable("advisor.townstead.hide"), mouseX, mouseY);
        y += 11;
        if (steps) {
            String now = (String) tip.args()[2];
            for (Step st : tip.steps()) {
                if (compact && !st.key().equals(now)) continue;
                boolean current = st.key().equals(now);
                String mark = st.done() ? "✔ " : current ? "▸ " : "· ";
                int color = st.done() ? 0xFF7FBF6A : current ? 0xFFFFFFFF : 0xFF8A8A8A;
                Component label = Component.translatable("advisor.townstead.step." + st.key(), tip.args()[4]);
                g.text(font, Component.literal(mark).append(label), left + PAD + 2, y, color, true);
                y += 10;
            }
            y += 3;
        }
        for (FormattedCharSequence line : body) {
            g.text(font, line, left + PAD, y, 0xFFE6E6E6, true);
            y += font.lineHeight + 1;
        }
        rowY = y + 3;
        Component count = Component.translatable("advisor.townstead.count", Math.floorMod(index, tips.size()) + 1, tips.size());
        g.text(font, count, left + PAD, rowY, 0xFF9A9A9A, true);
        Component next = Component.translatable("advisor.townstead.next");
        nextX0 = left + PAD + font.width(count) + 8;
        nextX1 = nextX0 + font.width(next);
        boolean hotNext = mouseX >= nextX0 && mouseX < nextX1 && mouseY >= rowY && mouseY < rowY + 10;
        if (tips.size() > 1) g.text(font, next, nextX0, rowY, hotNext ? 0xFFFFFFFF : 0xFF9EE37D, true);
        Component dismiss = Component.translatable(steps ? "advisor.townstead.steps.hide" : "advisor.townstead.dismiss");
        dismissX0 = left + W - PAD - font.width(dismiss);
        boolean hotDismiss = mouseX >= dismissX0 && mouseX < left + W && mouseY >= rowY && mouseY < rowY + 10;
        g.text(font, dismiss, dismissX0, rowY, hotDismiss ? 0xFFFFFFFF : 0xFFB0B0B0, true);
        if (hotDismiss) g.setTooltipForNextFrame(font, Component.translatable(steps ? "advisor.townstead.steps.hide.tip" : "advisor.townstead.dismiss.tip"), mouseX, mouseY);
    }

    private Advisor() {}
}
