package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.economy.Budget;
import com.cloudkingtv.townstead.economy.Finance;
import com.cloudkingtv.townstead.network.EconomyPayloads;
import com.cloudkingtv.townstead.sim.TownModel;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * The Town Hall's treasury: what the town has, what it makes a day, and emeralds in and out. Taxes, budgets and
 * loans are set in the planner's budget panel; this is the till.
 */
public class TownHallScreen extends Screen {
    private static final Identifier PANEL = Townstead.id("planner/panel");
    private static final int W = 230, H = 142;

    private final UUID cityId;
    private final BlockPos pos;
    private Finance.@org.jspecify.annotations.Nullable Report day;
    private long dayAt;

    public TownHallScreen(UUID cityId, BlockPos pos) {
        super(Component.translatable("screen.townstead.town_hall"));
        this.cityId = cityId;
        this.pos = pos;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() { return (width - W) / 2; }
    private int top() { return (height - H) / 2; }

    @Override
    protected void init() {
        int x = left() + 8, y = top() + H - 52, w = (W - 16 - 8) / 3;
        int[] amounts = {1, 10, 64};
        for (int i = 0; i < amounts.length; i++) {
            int n = amounts[i];
            addRenderableWidget(Button.builder(Component.translatable("screen.townstead.town_hall.withdraw", n),
                    b -> ClientPacketDistributor.sendToServer(new EconomyPayloads.Withdraw(pos, n)))
                    .bounds(x + i * (w + 4), y, w, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.townstead.town_hall.deposit_all"),
                b -> ClientPacketDistributor.sendToServer(new EconomyPayloads.DepositAll(pos)))
                .bounds(x, y + 24, W - 16, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        CitySummary city = ClientCityCache.byId(cityId).orElse(null);
        if (city == null) { onClose(); return; }
        int x = left() + 8, y = top() + 8;
        g.text(font, Component.translatable("screen.townstead.town_hall.title", city.name()), x, y, 0xFFFFE9B0, true);
        g.item(new ItemStack(Items.EMERALD), x, y + 14);
        g.text(font, Component.translatable("screen.townstead.town_hall.treasury", Budget.format(city.treasury())), x + 20, y + 18,
                city.treasury() < 0 ? 0xFFFF8A7A : 0xFFF2F2F2, true);
        if (day == null || net.minecraft.util.Util.getMillis() - dayAt > 1000) {
            dayAt = net.minecraft.util.Util.getMillis();
            day = Finance.daily(TownModel.of(city.id(), ClientLand.get().buildingsOf(city.id()), ClientRoads.network(), city.budget(),
                    city.treasury(), city.tiles(), city.townHall(), city.civics()));
        }
        Finance.Report day = this.day;
        g.text(font, Component.translatable("screen.townstead.town_hall.daily", Budget.signed(day.income()), Budget.signed(-day.expenses()),
                Budget.signed(day.net())), x, y + 36, day.net() >= 0 ? 0xFF9EE37D : 0xFFFF8A7A, true);
        g.text(font, Component.translatable("screen.townstead.town_hall.available",
                Budget.format(com.cloudkingtv.townstead.economy.TownEconomy.withdrawable(city.treasury(), city.budget()))), x, y + 50, 0xFFDDDDDD, true);
        g.text(font, Component.translatable("screen.townstead.town_hall.hint"), x, y + 62, 0xFF9A9A9A, true);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        g.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, left(), top(), W, H);
    }
}
