package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.Residents;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.client.ClientLand;
import com.cloudkingtv.townstead.district.District;
import com.cloudkingtv.townstead.district.Policy;
import com.cloudkingtv.townstead.district.Specialization;
import com.cloudkingtv.townstead.network.DistrictPayloads;
import com.cloudkingtv.townstead.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * Policies, as in Cities: Skylines: the whole town's (P), or the selected district's while the districts tool is out,
 * with its specialisation. Click a policy to switch it on or off; hover for what it does and costs.
 */
final class PolicyPanel {
    private static final Identifier PANEL = Townstead.id("planner/panel");
    private static final int W = 214, ROW = 13, PAD = 6;
    static boolean townOpen;
    private static int left, top, h;
    private record Hit(int x0, int y0, int x1, int y1, Runnable run) {}
    private static final List<Hit> HITS = new ArrayList<>();

    static boolean contains(double x, double y) {
        return h > 0 && x >= left && x < left + W && y >= top && y < top + h;
    }

    static boolean click(double x, double y) {
        if (!contains(x, y)) return false;
        for (Hit hit : HITS) {
            if (x >= hit.x0 && x < hit.x1 && y >= hit.y0 && y < hit.y1) {
                hit.run.run();
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, 0.4f));
                return true;
            }
        }
        return true;
    }

    /** Draws the panel for a district, or (district null) the town. Nothing if neither is wanted. */
    static void draw(GuiGraphicsExtractor g, Font font, @Nullable District district, int topBar, int mouseX, int mouseY) {
        HITS.clear();
        h = 0;
        CitySummary city = PlannerClient.city().orElse(null);
        if (city == null || district == null && !townOpen) return;
        List<Policy> shown = new ArrayList<>();
        for (Policy p : Policy.values()) if (district == null || p.local) shown.add(p);
        Set<Policy> on = district == null ? city.policies() : district.policies();
        // The town panel's footer hint wraps to the panel's width.
        List<FormattedCharSequence> hint = district == null ? font.split(Component.translatable("policy.townstead.hint"), W - PAD * 2) : List.of();
        h = PAD * 2 + (1 + shown.size()) * ROW + (district != null ? 4 * ROW : hint.size() * (font.lineHeight + 1)) + 4;
        left = 6;
        top = topBar + 4;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left, top, W, h);
        int x = left + PAD, y = top + PAD;
        if (district != null) {
            g.fill(x, y, x + 8, y + 8, 0xFF000000 | district.rgb());
            g.text(font, Component.literal(district.name()), x + 12, y, 0xFFFFE9B0, true);
            y += ROW;
            int people = 0;
            for (Building b : ClientLand.get().buildingsOf(city.id())) {
                if (b.working() && district.cells().contains(ZoneRules.cellKey(b.cellX, b.cellZ))) people += Residents.people(b);
            }
            g.text(font, Component.translatable("district.townstead.stats", district.cells().size(), people), x, y, 0xFFB8B8B8, true);
            y += ROW;
            // Specialisation: four buttons.
            g.text(font, Component.translatable("district.townstead.spec"), x, y + 2, 0xFFDDDDDD, true);
            int bx = x + font.width(Component.translatable("district.townstead.spec")) + 6;
            Component tip = null;
            for (Specialization s : Specialization.values()) {
                boolean sel = district.spec() == s, hot = mouseX >= bx && mouseX < bx + 16 && mouseY >= y && mouseY < y + 16;
                g.fill(bx - 1, y - 1, bx + 17, y + 17, sel ? 0xFF9EE37D : hot ? 0x60FFFFFF : 0x30FFFFFF);
                g.fill(bx, y, bx + 16, y + 16, 0xFF2A2724);
                g.item(s.icon(), bx, y);
                if (hot) tip = Component.translatable("specialization.townstead.tip", s.displayName(), Component.translatable("specialization.townstead." + s.key() + ".desc"));
                int id = district.id(), ord = s.ordinal();
                HITS.add(new Hit(bx, y, bx + 16, y + 16, () -> send(new DistrictPayloads.SetSpec(city.id(), id, ord))));
                bx += 19;
            }
            y += ROW + 6;
            if (tip != null) g.setTooltipForNextFrame(font, tip, mouseX, mouseY);
        } else {
            g.text(font, Component.translatable("policy.townstead.town", city.name()), x, y, 0xFFFFE9B0, true);
            y += ROW;
        }
        Component tip = null;
        for (Policy p : shown) {
            boolean active = on.contains(p), hot = mouseX >= x && mouseX < left + W - PAD && mouseY >= y - 1 && mouseY < y + ROW - 1;
            if (hot) g.fill(x - 2, y - 2, left + W - PAD + 1, y + ROW - 2, 0x30FFFFFF);
            g.pose().pushMatrix();
            g.pose().translate(x, y - 2);
            g.pose().scale(0.625f, 0.625f);
            g.item(p.icon(), 0, 0);
            g.pose().popMatrix();
            g.text(font, p.displayName(), x + 13, y, active ? 0xFFFFFFFF : 0xFFB0B0B0, true);
            Component state = Component.translatable(active ? "policy.townstead.on" : "policy.townstead.off");
            int sx = left + W - PAD - font.width(state) - 4;
            g.fill(sx - 3, y - 2, left + W - PAD, y + ROW - 3, active ? 0xFF3E7A35 : 0xFF4A4440);
            g.text(font, state, sx, y, active ? 0xFFDDFFD0 : 0xFFB8B8B8, true);
            if (hot) tip = p.description();
            int id = district == null ? -1 : district.id(), ord = p.ordinal();
            HITS.add(new Hit(x - 2, y - 2, left + W - PAD + 1, y + ROW - 2, () -> send(new DistrictPayloads.SetPolicy(city.id(), id, ord, !active))));
            y += ROW;
        }
        if (district != null) {
            y += 2;
            Component del = Component.translatable("district.townstead.delete");
            boolean hot = mouseX >= x && mouseX < x + font.width(del) + 6 && mouseY >= y - 2 && mouseY < y + ROW - 2;
            g.fill(x - 2, y - 2, x + font.width(del) + 4, y + ROW - 3, hot ? 0xFF8A3A30 : 0xFF5A3430);
            g.text(font, del, x + 1, y, 0xFFFFD0C8, true);
            int id = district.id();
            HITS.add(new Hit(x - 2, y - 2, x + font.width(del) + 4, y + ROW - 2, () -> {
                send(new DistrictPayloads.SetSpec(city.id(), id, -1));
                DistrictTool.selected = -1;
            }));
        } else {
            for (int i = 0; i < hint.size(); i++) g.text(font, hint.get(i), x, y + 2 + i * (font.lineHeight + 1), 0xFF8A8A8A, true);
        }
        if (tip != null) g.setTooltipForNextFrame(font, font.split(tip, 220), mouseX, mouseY);
    }

    private static void send(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        ClientPacketDistributor.sendToServer(payload);
    }

    private PolicyPanel() {}
}
