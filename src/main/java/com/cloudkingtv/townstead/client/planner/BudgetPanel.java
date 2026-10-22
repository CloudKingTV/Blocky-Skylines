package com.cloudkingtv.townstead.client.planner;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.economy.Budget;
import com.cloudkingtv.townstead.economy.Finance;
import com.cloudkingtv.townstead.economy.Loan;
import com.cloudkingtv.townstead.network.EconomyPayloads;
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
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * The town budget, Cities: Skylines style: income by tax kind with its rate, upkeep by department with its budget,
 * roads and loans, the loan offers, and a bar per recent settlement; and on its second page the town's production.
 * Every number comes from {@link Finance} and the industry on the same {@link TownModel} the server settles with.
 * Arrows (or the scroll wheel over a row) change rates and budgets.
 */
final class BudgetPanel {
    private static final Identifier PANEL = Townstead.id("planner/panel");
    private static final int ROW = 11, PAD = 7, MAX_W = 380;
    private static final int TITLE = 0xFFFFE9B0, TEXT = 0xFFDDDDDD, DIM = 0xFF9A9A9A, GOOD = 0xFF9EE37D, BAD = 0xFFFF8A7A, HOT = 0xFFFFFFFF;
    private static final ZoneType[] TAX_ZONES = {ZoneType.RESIDENTIAL, ZoneType.COMMERCIAL, ZoneType.INDUSTRIAL, ZoneType.OFFICE};

    static boolean open;
    /** 0: money, 1: production. */
    static int page;

    /** A clickable area drawn this frame; {@code step} is what one click (or scroll notch up) adds. */
    private record Hit(int x0, int y0, int x1, int y1, Runnable click, @Nullable Runnable up, @Nullable Runnable down) {
        boolean contains(double x, double y) { return x >= x0 && x < x1 && y >= y0 && y < y1; }
    }

    private static final List<Hit> HITS = new ArrayList<>();
    private static int left, top, w, h;

    static boolean contains(double x, double y) {
        return open && x >= left && x < left + w && y >= top && y < top + h;
    }

    static boolean click(double x, double y) {
        if (!contains(x, y)) return false;
        for (Hit hit : HITS) if (hit.contains(x, y)) { hit.click().run(); return true; }
        return true; // clicks on the panel never reach the world
    }

    static boolean scroll(double x, double y, double amount) {
        if (!contains(x, y)) return false;
        for (Hit hit : HITS) {
            if (!hit.contains(x, y)) continue;
            Runnable r = amount > 0 ? hit.up() : hit.down();
            if (r != null) r.run();
        }
        return true;
    }

    static void draw(GuiGraphicsExtractor g, Font font, int screenW, int screenH, int topBar, int bottom, int mouseX, int mouseY) {
        HITS.clear();
        CitySummary city = PlannerClient.city().orElse(null);
        TownModel m = PlannerClient.model();
        if (!open || city == null || m == null) return;
        if (page == 1) { production(g, font, city, m, screenW, topBar, bottom, mouseX, mouseY); return; }
        Finance.Report day = Finance.daily(m);
        Budget b = city.budget();

        w = Math.min(MAX_W, screenW - 12);
        int col = (w - PAD * 3) / 2;
        int rows = 2 + 1 + Math.max(TAX_ZONES.length + 3 + Loan.Offer.values().length + 1, ServiceType.Department.values().length + 4);
        int graphH = 26;
        h = PAD * 2 + rows * ROW + graphH + 6;
        if (h > bottom - topBar - 4) { graphH = 0; h = PAD * 2 + rows * ROW; }
        left = (screenW - w) / 2;
        top = topBar + 4;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left, top, w, h);

        int x = left + PAD, y = top + PAD;
        g.text(font, Component.translatable("budget.townstead.title", city.name()), x, y, TITLE, true);
        tabs(g, font, mouseX, mouseY);
        closeButton(g, font, mouseX, mouseY);
        y += ROW;
        g.text(font, Component.translatable("budget.townstead.treasury", Budget.format(city.treasury())), x, y, city.treasury() < 0 ? BAD : TEXT, true);
        Component net = Component.translatable("budget.townstead.net", Budget.signed(day.income()), Budget.signed(-day.expenses()), Budget.signed(day.net()));
        g.text(font, net, left + w - PAD - font.width(net), y, day.net() >= 0 ? GOOD : BAD, true);
        y += ROW;
        if (m.broke()) {
            g.text(font, Component.translatable("budget.townstead.broke"), x, y, BAD, true);
        }
        y += ROW;

        // Left column: income by tax, then loans.
        int ly = y, rx = left + PAD * 2 + col;
        g.text(font, Component.translatable("budget.townstead.income"), x, ly, TITLE, true);
        ly += ROW;
        for (int i = 0; i < TAX_ZONES.length; i++) {
            int kind = i;
            int rate = b.tax(kind);
            row(g, font, x, ly, col, TAX_ZONES[i].icon(), Component.translatable("budget.townstead.tax_" + kind),
                    rate + "%", Budget.signed(day.taxes()[kind]), GOOD, mouseX, mouseY,
                    () -> send(EconomyPayloads.Action.TAX, kind, rate - 1), () -> send(EconomyPayloads.Action.TAX, kind, rate + 1));
            ly += ROW;
        }
        // Coach fares: no rate to set, just what the riders pay.
        g.pose().pushMatrix();
        g.pose().translate(x, ly - 2);
        g.pose().scale(0.625f, 0.625f);
        g.item(new ItemStack(Items.MINECART), 0, 0);
        g.pose().popMatrix();
        g.text(font, Component.translatable("budget.townstead.fares", m.traffic.riders()), x + 13, ly, TEXT, true);
        String fares = Budget.signed(day.fares());
        g.text(font, fares, x + col - font.width(fares), ly, day.fares() == 0 ? DIM : GOOD, true);
        ly += ROW;
        // Exports: the industry's surplus beyond the storehouse's room, sold.
        g.pose().pushMatrix();
        g.pose().translate(x, ly - 2);
        g.pose().scale(0.625f, 0.625f);
        g.item(new ItemStack(Items.CHEST_MINECART), 0, 0);
        g.pose().popMatrix();
        g.text(font, Component.translatable("budget.townstead.exports"), x + 13, ly, TEXT, true);
        String exports = Budget.signed(day.exports());
        g.text(font, exports, x + col - font.width(exports), ly, day.exports() == 0 ? DIM : GOOD, true);
        HITS.add(new Hit(x, ly - 1, x + col, ly + ROW - 1, () -> page = 1, null, null));
        ly += ROW + 2;
        g.text(font, Component.translatable("budget.townstead.loans"), x, ly, TITLE, true);
        ly += ROW;
        for (Loan.Offer offer : Loan.Offer.values()) {
            Loan loan = b.loans.stream().filter(l -> l.offer() == offer).findFirst().orElse(null);
            Component label = loan == null
                    ? Component.translatable("budget.townstead.offer", Budget.format(offer.amount), offer.interest, offer.days)
                    : Component.translatable("budget.townstead.owed", Component.translatable("economy.townstead.loan." + offer.key()), Budget.format(loan.owed()));
            g.text(font, label, x, ly, loan == null ? TEXT : DIM, true);
            Component action = Component.translatable(loan == null ? "budget.townstead.take" : "budget.townstead.repay");
            int bx = x + col - font.width(action) - 2;
            boolean hot = mouseX >= bx - 2 && mouseX < x + col && mouseY >= ly - 1 && mouseY < ly + ROW - 1;
            g.fill(bx - 2, ly - 1, x + col, ly + ROW - 2, hot ? 0x60FFFFFF : 0x30FFFFFF);
            g.text(font, action, bx, ly, hot ? HOT : TITLE, true);
            int idx = offer.ordinal();
            HITS.add(new Hit(bx - 2, ly - 1, x + col, ly + ROW - 1,
                    () -> send(loan == null ? EconomyPayloads.Action.TAKE_LOAN : EconomyPayloads.Action.REPAY_LOAN, idx, 0), null, null));
            ly += ROW;
        }

        // Right column: upkeep by department, roads, loan payments.
        int ry = y;
        g.text(font, Component.translatable("budget.townstead.upkeep"), rx, ry, TITLE, true);
        ry += ROW;
        for (ServiceType.Department d : ServiceType.Department.values()) {
            int pct = b.budget(d);
            int dep = d.ordinal();
            row(g, font, rx, ry, col, d.icon(), d.displayName(), pct + "%", Budget.signed(-day.upkeep()[dep]),
                    day.upkeep()[dep] == 0 ? DIM : BAD, mouseX, mouseY,
                    () -> send(EconomyPayloads.Action.BUDGET, dep, pct - 10), () -> send(EconomyPayloads.Action.BUDGET, dep, pct + 10));
            ry += ROW;
        }
        plain(g, font, rx, ry, col, Component.translatable("budget.townstead.roads"), Budget.signed(-day.roads()), day.roads() == 0 ? DIM : BAD);
        ry += ROW;
        plain(g, font, rx, ry, col, Component.translatable("budget.townstead.loan_payments"), Budget.signed(-day.loans()), day.loans() == 0 ? DIM : BAD);
        ry += ROW;
        plain(g, font, rx, ry, col, Component.translatable("budget.townstead.policies"), Budget.signed(-day.policies()), day.policies() == 0 ? DIM : BAD);

        if (graphH > 0) ledger(g, font, left + PAD, top + h - PAD - graphH, w - PAD * 2, graphH, b.ledger, mouseX, mouseY);
    }

    /**
     * The production page: for each material the storehouse holds or the industry touches, what's in stock against
     * the room for it, what's made, used and sold a day, how long the stock lasts at that rate, and a button to buy
     * some in. Above it, the industry's buildings and what they're short of.
     */
    private static void production(GuiGraphicsExtractor g, Font font, CitySummary city, TownModel m, int screenW, int topBar, int bottom,
                                   int mouseX, int mouseY) {
        var day = m.industry();
        List<com.cloudkingtv.townstead.road.Material> mats = new ArrayList<>();
        for (var mat : com.cloudkingtv.townstead.road.Material.values()) {
            if (m.stock.getOrDefault(mat, 0) > 0 || day.made(mat) > 0 || day.used(mat) > 0) mats.add(mat);
        }
        w = Math.min(MAX_W, screenW - 12);
        h = PAD * 2 + ROW * (5 + Math.max(1, mats.size())) + 4;
        left = (screenW - w) / 2;
        top = topBar + 4;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left, top, w, h);
        int x = left + PAD, y = top + PAD, right = left + w - PAD;
        g.text(font, Component.translatable("budget.townstead.production_title", city.name()), x, y, TITLE, true);
        tabs(g, font, mouseX, mouseY);
        closeButton(g, font, mouseX, mouseY);
        y += ROW;
        int raw = 0, proc = 0, starved = 0;
        for (var f : day.flows()) {
            if (f.trade().raw) raw++;
            else proc++;
            if (f.shortOf() != null) starved++;
        }
        g.text(font, Component.translatable("budget.townstead.industry_summary", raw, proc, day.capacity(), m.storehouses),
                x, y, TEXT, true);
        y += ROW;
        Component sold = Component.translatable("budget.townstead.exports_day", Budget.signed(Math.round(day.exportValue())));
        g.text(font, starved > 0 ? Component.translatable("budget.townstead.starved", starved) : Component.translatable("budget.townstead.all_supplied"),
                x, y, starved > 0 ? BAD : DIM, true);
        g.text(font, sold, right - font.width(sold), y, day.exportValue() > 0 ? GOOD : DIM, true);
        y += ROW + 3;
        // Column heads.
        int cStock = x + 96, cMade = x + 150, cUsed = x + 186, cSold = x + 222, cDays = x + 258, cBuy = right - 58;
        g.text(font, Component.translatable("budget.townstead.col_material"), x, y, DIM, false);
        g.text(font, Component.translatable("budget.townstead.col_stock"), cStock, y, DIM, false);
        g.text(font, Component.translatable("budget.townstead.col_made"), cMade, y, DIM, false);
        g.text(font, Component.translatable("budget.townstead.col_used"), cUsed, y, DIM, false);
        g.text(font, Component.translatable("budget.townstead.col_sold"), cSold, y, DIM, false);
        g.text(font, Component.translatable("budget.townstead.col_days"), cDays, y, DIM, false);
        y += ROW;
        if (mats.isEmpty()) g.text(font, Component.translatable("budget.townstead.no_industry"), x, y, DIM, true);
        for (var mat : mats) {
            int have = m.stock.getOrDefault(mat, 0);
            g.pose().pushMatrix();
            g.pose().translate(x, y - 2);
            g.pose().scale(0.625f, 0.625f);
            g.item(mat.icon(), 0, 0);
            g.pose().popMatrix();
            g.text(font, mat.displayName(), x + 13, y, TEXT, true);
            boolean full = have >= day.capacity();
            g.text(font, have + "/" + day.capacity(), cStock, y, full ? TITLE : TEXT, true);
            g.text(font, amount(day.made(mat)), cMade, y, day.made(mat) > 0 ? GOOD : DIM, true);
            g.text(font, amount(day.used(mat)), cUsed, y, day.used(mat) > 0 ? BAD : DIM, true);
            g.text(font, amount(day.exported(mat)), cSold, y, day.exported(mat) > 0 ? GOOD : DIM, true);
            double net = day.net(mat);
            String days = net >= 0 ? "∞" : String.valueOf((int) Math.floor(have / -net));
            g.text(font, days, cDays, y, net >= 0 ? DIM : have / -net < 2 ? BAD : TEXT, true);
            // Buy a stack in, at the import price.
            int cost = 64 * mat.importPrice();
            Component buy = Component.translatable("budget.townstead.buy", Budget.format(cost));
            int bx = cBuy;
            boolean hot = mouseX >= bx - 2 && mouseX < right && mouseY >= y - 1 && mouseY < y + ROW - 1;
            boolean afford = city.treasury() >= cost;
            g.fill(bx - 2, y - 1, right, y + ROW - 2, hot && afford ? 0x60FFFFFF : 0x30FFFFFF);
            g.text(font, buy, bx, y, !afford ? DIM : hot ? HOT : TITLE, true);
            int idx = mat.ordinal();
            HITS.add(new Hit(bx - 2, y - 1, right, y + ROW - 1, () -> send(EconomyPayloads.Action.BUY, idx, 64), null, null));
            if (hot) g.setTooltipForNextFrame(font, Component.translatable("budget.townstead.buy_tip", mat.displayName(), Budget.format(mat.importPrice()),
                    Budget.format(mat.price)), mouseX, mouseY);
            y += ROW;
        }
    }

    /** "12" or "0.5": a day's amount, whole once it's worth counting. */
    private static String amount(double v) {
        if (v <= 0.05) return "-";
        return v >= 10 ? String.valueOf(Math.round(v)) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** "Money · Production" by the title. */
    private static void tabs(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        Component[] names = {Component.translatable("budget.townstead.tab_money"), Component.translatable("budget.townstead.tab_production")};
        int tx = left + w - 20;
        for (int i = names.length - 1; i >= 0; i--) {
            int tw = font.width(names[i]);
            tx -= tw;
            int ty = top + 5, index = i;
            boolean hot = mouseX >= tx - 3 && mouseX < tx + tw + 3 && mouseY >= ty - 2 && mouseY < ty + 10;
            if (page == i) g.fill(tx - 3, ty - 2, tx + tw + 3, ty + 10, 0x50FFFFFF);
            g.text(font, names[i], tx, ty, page == i ? HOT : hot ? TEXT : DIM, true);
            HITS.add(new Hit(tx - 3, ty - 2, tx + tw + 3, ty + 10, () -> page = index, null, null));
            tx -= 10;
        }
    }

    /** A line with an icon, a label, a stepper (‹ value ›) and an amount on the right. */
    private static void row(GuiGraphicsExtractor g, Font font, int x, int y, int w, ItemStack icon, Component label, String value, String amount,
                            int amountColor, int mouseX, int mouseY, Runnable less, Runnable more) {
        g.pose().pushMatrix();
        g.pose().translate(x, y - 2);
        g.pose().scale(0.625f, 0.625f);
        g.item(icon, 0, 0);
        g.pose().popMatrix();
        g.text(font, label, x + 13, y, TEXT, true);
        int ax = x + w - font.width(amount);
        g.text(font, amount, ax, y, amountColor, true);
        int sx = x + w - 44 - 46; // stepper: ‹ 100% ›
        boolean hotL = mouseX >= sx - 3 && mouseX < sx + 7 && mouseY >= y - 1 && mouseY < y + ROW - 1;
        boolean hotR = mouseX >= sx + 33 && mouseX < sx + 43 && mouseY >= y - 1 && mouseY < y + ROW - 1;
        g.text(font, "‹", sx, y, hotL ? HOT : TITLE, true);
        g.centeredText(font, Component.literal(value), sx + 20, y, TEXT);
        g.text(font, "›", sx + 36, y, hotR ? HOT : TITLE, true);
        HITS.add(new Hit(sx - 3, y - 1, sx + 7, y + ROW - 1, less, null, null));
        HITS.add(new Hit(sx + 33, y - 1, sx + 43, y + ROW - 1, more, null, null));
        HITS.add(new Hit(x, y - 1, x + w, y + ROW - 1, () -> {}, more, less)); // scroll anywhere on the row
    }

    private static void plain(GuiGraphicsExtractor g, Font font, int x, int y, int w, Component label, String amount, int color) {
        g.text(font, label, x + 13, y, TEXT, true);
        g.text(font, amount, x + w - font.width(amount), y, color, true);
    }

    /** One bar per recent settlement: green above the line for a surplus, red below for a deficit. */
    private static void ledger(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, List<Budget.Entry> ledger, int mouseX, int mouseY) {
        g.fill(x, y, x + w, y + h, 0x40101010);
        int mid = y + h / 2;
        g.fill(x, mid, x + w, mid + 1, 0x60FFFFFF);
        if (ledger.isEmpty()) {
            g.centeredText(font, Component.translatable("budget.townstead.no_history"), x + w / 2, mid - 4, DIM);
            return;
        }
        long max = 1;
        for (Budget.Entry e : ledger) max = Math.max(max, Math.abs(e.income() - e.expenses()));
        int bw = Math.max(2, w / Budget.LEDGER_SIZE);
        int x0 = x + w - ledger.size() * bw;
        for (int i = 0; i < ledger.size(); i++) {
            Budget.Entry e = ledger.get(i);
            long net = e.income() - e.expenses();
            int bh = (int) Math.round((h / 2.0 - 2) * Math.abs(net) / max);
            int bx = x0 + i * bw;
            if (net >= 0) g.fill(bx, mid - bh, bx + bw - 1, mid, 0xFF6FCB5B);
            else g.fill(bx, mid + 1, bx + bw - 1, mid + 1 + bh, 0xFFE0604F);
            if (mouseX >= bx && mouseX < bx + bw && mouseY >= y && mouseY < y + h) {
                g.setTooltipForNextFrame(font, Component.translatable("budget.townstead.settlement", Budget.signed(e.income()),
                        Budget.signed(-e.expenses()), Budget.format(e.treasury())), mouseX, mouseY);
            }
        }
        g.text(font, Component.translatable("budget.townstead.history"), x + 3, y + 2, DIM, false);
    }

    private static void closeButton(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
        int cx = left + w - 13, cy = top + 4;
        boolean hot = mouseX >= cx - 2 && mouseX < cx + 10 && mouseY >= cy && mouseY < cy + 12;
        g.text(font, "×", cx, cy, hot ? HOT : 0xFFB0B0B0, true);
        HITS.add(new Hit(cx - 2, cy, cx + 10, cy + 12, () -> open = false, null, null));
    }

    private static void send(EconomyPayloads.Action action, int index, int value) {
        PlannerClient.city().ifPresent(c -> ClientPacketDistributor.sendToServer(new EconomyPayloads.Edit(c.id(), action, index, value)));
        var mc = net.minecraft.client.Minecraft.getInstance();
        mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.2f, 0.3f));
    }

    private BudgetPanel() {}
}
