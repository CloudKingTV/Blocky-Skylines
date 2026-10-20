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
 * worked out afresh from the town every frame, so they follow what the player does. "Next" shows the other tips
 * that apply; the cross hides the advisor until it's turned back on with the ? key.
 */
final class Advisor {
    private static final Identifier PANEL = Townstead.id("planner/panel");
    private static final int W = 176, PAD = 6;
    /** Which of the tips that apply is shown (Next cycles). */
    private static int index;
    private static int left, top, h;
    private static int nextX0, nextX1, closeX0, rowY;

    record Tip(String key, Object[] args) {
        Component title() { return Component.translatable("advisor.townstead." + key + ".title", args); }
        Component body() { return Component.translatable("advisor.townstead." + key, args); }
    }

    static boolean shown() {
        return TownsteadConfig.SHOW_ADVISOR.get();
    }

    static void toggle() {
        TownsteadConfig.SHOW_ADVISOR.set(!shown());
        TownsteadConfig.SHOW_ADVISOR.save();
        index = 0;
    }

    /** Every tip that applies to the town now, most pressing first. */
    static List<Tip> tips(CitySummary city, @Nullable TownModel m) {
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

    /** Next / close; true if the click was the advisor's. */
    static boolean click(double x, double y) {
        if (!contains(x, y)) return false;
        if (y >= rowY && y < rowY + 10) {
            if (x >= nextX0 && x < nextX1) index++;
            else if (x >= closeX0) toggle();
        }
        return true;
    }

    /** Draws the card with its bottom edge at {@code bottom}. */
    static void draw(GuiGraphicsExtractor g, Font font, int bottom, int mouseX, int mouseY) {
        h = 0;
        if (!shown()) return;
        CitySummary city = PlannerClient.city().orElse(null);
        if (city == null) return;
        List<Tip> tips = tips(city, PlannerClient.model());
        if (tips.isEmpty()) return;
        Tip tip = tips.get(Math.floorMod(index, tips.size()));
        List<FormattedCharSequence> body = font.split(tip.body(), W - PAD * 2);
        h = PAD * 2 + 11 + body.size() * (font.lineHeight + 1) + 13;
        left = 6;
        top = bottom - h;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left, top, W, h);
        int y = top + PAD;
        g.text(font, Component.literal("» ").append(tip.title()), left + PAD, y, 0xFFFFE9B0, true);
        y += 11;
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
        Component close = Component.translatable("advisor.townstead.hide");
        closeX0 = left + W - PAD - font.width(close);
        boolean hotClose = mouseX >= closeX0 && mouseX < left + W && mouseY >= rowY && mouseY < rowY + 10;
        g.text(font, close, closeX0, rowY, hotClose ? 0xFFFFFFFF : 0xFFB0B0B0, true);
    }

    private Advisor() {}
}
