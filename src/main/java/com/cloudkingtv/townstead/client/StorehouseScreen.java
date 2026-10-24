package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.city.Stock;
import com.cloudkingtv.townstead.network.StorehousePayloads;
import com.cloudkingtv.townstead.road.Material;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The Town Storehouse: what the town has to build with. Click an item to take a stack out; the button deposits
 * every building material you carry. The list updates live from the city snapshot.
 */
public class StorehouseScreen extends Screen {
    private static final Identifier PANEL = Townstead.id("planner/panel");
    private static final Identifier SLOT_SPRITE = Townstead.id("planner/button");
    private static final Identifier SLOT_HOVER = Townstead.id("planner/button_hover");
    private static final int COLS = 9, ROWS = 4, CELL = 20, W = COLS * CELL + 16;
    /** Tall enough for the grid, the materials list (two columns) and the deposit button. */
    private static final int H = 30 + ROWS * CELL + 6 + (Material.values().length + 1) / 2 * 10 + 6 + 28;

    private final UUID cityId;
    private final BlockPos pos;
    private int scroll;

    public StorehouseScreen(UUID cityId, BlockPos pos) {
        super(Component.translatable("screen.townstead.storehouse"));
        this.cityId = cityId;
        this.pos = pos;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }
    private int gridTop() { return top() + 30; }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("screen.townstead.storehouse.deposit_all"),
                b -> ClientPacketDistributor.sendToServer(new StorehousePayloads.DepositAll(pos)))
                .bounds(left() + 8, top() + H - 28, W - 16, 20).build());
    }

    private List<Map.Entry<Identifier, Integer>> entries(CitySummary city) {
        List<Map.Entry<Identifier, Integer>> list = new ArrayList<>(city.stock().entrySet());
        list.sort(Comparator.comparingInt((Map.Entry<Identifier, Integer> e) -> e.getValue()).reversed()
                .thenComparing(e -> e.getKey().toString()));
        return list;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        CitySummary city = ClientCityCache.byId(cityId).orElse(null);
        if (city == null) { onClose(); return; }
        int x = left(), y = top();
        g.text(font, Component.translatable("screen.townstead.storehouse.title", city.name()), x + 8, y + 8, 0xFFFFE9B0, true);
        int total = city.stock().values().stream().mapToInt(Integer::intValue).sum();
        g.text(font, Component.translatable("screen.townstead.storehouse.total", total), x + 8, y + 18, 0xFFB8B8B8, true);

        List<Map.Entry<Identifier, Integer>> list = entries(city);
        int maxScroll = Math.max(0, Mth.ceil(list.size() / (float) COLS) - ROWS);
        scroll = Mth.clamp(scroll, 0, maxScroll);
        int hovered = slotAt(mouseX, mouseY);
        for (int i = 0; i < COLS * ROWS; i++) {
            int sx = x + 8 + (i % COLS) * CELL, sy = gridTop() + (i / COLS) * CELL;
            g.blitSprite(RenderPipelines.GUI_TEXTURED, i == hovered ? SLOT_HOVER : SLOT_SPRITE, sx, sy, CELL, CELL);
            int index = i + scroll * COLS;
            if (index >= list.size()) continue;
            var e = list.get(index);
            ItemStack stack = new ItemStack(Stock.item(e.getKey()));
            g.item(stack, sx + 2, sy + 2);
            String n = abbreviate(e.getValue());
            g.text(font, n, sx + CELL - 1 - font.width(n), sy + CELL - 9, 0xFFFFFFFF, true);
            if (i == hovered) {
                g.setTooltipForNextFrame(font, List.of(stack.getHoverName().getVisualOrderText(),
                        Component.translatable("screen.townstead.storehouse.count", e.getValue()).getVisualOrderText(),
                        Component.translatable("screen.townstead.storehouse.take").getVisualOrderText()), mouseX, mouseY);
            }
        }
        if (list.isEmpty()) {
            g.centeredText(font, Component.translatable("screen.townstead.storehouse.empty"), x + W / 2, gridTop() + ROWS * CELL / 2 - 4, 0xFFB8B8B8);
        }
        // Materials by group: what the planner's bills are counted in.
        int ly = gridTop() + ROWS * CELL + 6, col = 0;
        for (Material m : Material.values()) {
            int n = Stock.count(city.stock(), m);
            Component line = Component.translatable("hud.townstead.stock_line", m.displayName(), abbreviate(n));
            g.text(font, line, x + 8 + col * (W / 2 - 4), ly, n > 0 ? 0xFFDDDDDD : 0xFF808080, true);
            if (++col == 2) { col = 0; ly += font.lineHeight + 1; }
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        // The panel belongs to the background pass so the deposit button draws on top of it.
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left(), top(), W, H);
    }

    private int slotAt(double mouseX, double mouseY) {
        int gx = (int) Math.floor((mouseX - left() - 8) / CELL), gy = (int) Math.floor((mouseY - gridTop()) / CELL);
        return gx >= 0 && gx < COLS && gy >= 0 && gy < ROWS ? gy * COLS + gx : -1;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        int slot = slotAt(event.x(), event.y());
        CitySummary city = ClientCityCache.byId(cityId).orElse(null);
        if (slot >= 0 && city != null) {
            List<Map.Entry<Identifier, Integer>> list = entries(city);
            int index = slot + scroll * COLS;
            if (index < list.size()) {
                ClientPacketDistributor.sendToServer(new StorehousePayloads.Withdraw(pos, list.get(index).getKey(), 64));
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, 0.3f));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        scroll -= (int) Math.signum(scrollY);
        return true;
    }

    private static String abbreviate(int n) {
        if (n >= 10_000) return (n / 1000) + "k";
        if (n >= 1000) return String.format(java.util.Locale.ROOT, "%.1fk", n / 1000f);
        return String.valueOf(n);
    }
}
