package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.progress.Milestone;
import com.cloudkingtv.townstead.road.RoadType;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.sim.TownModel;
import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The town's milestones (U): each title, the people it takes, its reward and land, and the icons of what it
 * unlocks; reached ones ticked, the next one with a progress bar.
 */
final class MilestonePanel {
    private static final Identifier PANEL = Townstead.id("planner/panel");
    private static final int ROW = 23, PAD = 7, W = 340;
    static boolean open;
    private static int left, top, h;

    static boolean contains(double x, double y) {
        return open && x >= left && x < left + W && y >= top && y < top + h;
    }

    static void draw(GuiGraphicsExtractor g, Font font, int screenW, int topBar, int bottom, int mouseX, int mouseY) {
        CitySummary city = PlannerClient.city().orElse(null);
        TownModel m = PlannerClient.model();
        if (!open || city == null || m == null) return;
        int people = m.demand.population();
        Milestone[] all = Milestone.values();
        h = Math.min(bottom - topBar - 8, PAD * 2 + 14 + all.length * ROW);
        left = (screenW - W) / 2;
        top = topBar + 4;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left, top, W, h);
        g.text(font, Component.translatable("milestone.townstead.panel", city.name(), people), left + PAD, top + PAD, 0xFFFFE9B0, true);
        int y = top + PAD + 14;
        Component tip = null;
        // Too many to show at once: keep the ones round the town's current milestone in view.
        int fit = Math.max(1, (h - PAD * 2 - 14) / ROW);
        int first = Math.max(0, Math.min(city.milestone() - 2, all.length - fit));
        for (Milestone ms : all) {
            if (ms.ordinal() < first) continue;
            if (y + ROW > top + h) break;
            boolean reached = ms.ordinal() <= city.milestone(), next = ms.ordinal() == city.milestone() + 1;
            int colour = reached ? 0xFF9EE37D : next ? 0xFFFFFFFF : 0xFF9A9A9A;
            g.text(font, Component.literal(reached ? "✔ " : "  ").append(ms.displayName()), left + PAD, y, colour, true);
            Component info = ms.ordinal() == 0 ? Component.translatable("milestone.townstead.start")
                    : Component.translatable("milestone.townstead.needs", ms.population, ms.reward, ms.tiles);
            g.text(font, info, left + PAD + 120, y, reached ? 0xFF8FB080 : 0xFFB8B8B8, true);
            if (next) {
                int bw = 100, fill = (int) Math.min(bw, (long) bw * people / Math.max(1, ms.population));
                g.fill(left + PAD + 12, y + 11, left + PAD + 12 + bw, y + 13, 0x80303030);
                g.fill(left + PAD + 12, y + 11, left + PAD + 12 + fill, y + 13, 0xFF9EE37D);
            }
            // What it unlocks, as icons.
            List<Object> things = new ArrayList<>();
            things.addAll(ms.roads);
            things.addAll(ms.zones);
            things.addAll(ms.services);
            int ix = left + PAD + 120;
            for (Object t : things) {
                ItemStack icon = t instanceof RoadType r ? r.icon() : t instanceof ZoneType z ? z.icon() : ((ServiceType) t).icon();
                g.pose().pushMatrix();
                g.pose().translate(ix, y + 10);
                g.pose().scale(0.75f, 0.75f);
                g.item(icon, 0, 0);
                g.pose().popMatrix();
                if (mouseX >= ix && mouseX < ix + 12 && mouseY >= y + 10 && mouseY < y + 22) {
                    tip = t instanceof RoadType r ? r.displayName() : t instanceof ZoneType z ? z.displayName() : ((ServiceType) t).displayName();
                }
                ix += 14;
            }
            y += ROW;
        }
        if (tip != null) g.setTooltipForNextFrame(font, tip, mouseX, mouseY);
    }

    private MilestonePanel() {}
}
