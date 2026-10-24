package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.client.planner.PlannerClient;
import com.cloudkingtv.townstead.registry.TownsteadRegistry;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.jspecify.annotations.Nullable;

/**
 * One short hint at the top of the screen for a player getting started, outside the planner: where to put the Town
 * Hall they're holding, the storehouse they're holding, and how to open the planner while their town has no roads
 * yet. Each goes away as soon as it's been acted on; the client setting "hudHints" turns them off.
 */
@EventBusSubscriber(modid = Townstead.MODID, value = Dist.CLIENT)
public final class OnboardingHud {
    private static final int WIDTH = 260;

    @SubscribeEvent
    static void register(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.BOSS_OVERLAY, Townstead.id("onboarding_hint"), (g, delta) -> draw(g));
    }

    /** The hint for the player now, or null. */
    static @Nullable Component hint(Minecraft mc) {
        if (mc.player == null || mc.level == null || PlannerClient.isActive() || !TownsteadConfig.HUD_HINTS.get()) return null;
        java.util.UUID me = mc.player.getUUID();
        CitySummary mine = ClientCityCache.all().stream().filter(c -> c.owner().equals(me)).findFirst().orElse(null);
        ItemStack held = mc.player.getMainHandItem();
        if (mine == null) {
            return held.is(TownsteadRegistry.TOWN_HALL_ITEM.get()) ? Component.translatable("hud.townstead.place_hall") : null;
        }
        if (held.is(TownsteadRegistry.TOWN_STOREHOUSE_ITEM.get())) return Component.translatable("hud.townstead.place_storehouse");
        boolean roads = ClientRoads.network().segments().stream().anyMatch(s -> s.city().equals(mine.id()));
        boolean inTown = mine.contains(mc.player.getBlockX(), mc.player.getBlockZ());
        if (!roads && inTown) return Component.translatable("hud.townstead.open_planner", TownsteadKeys.PLANNER.getTranslatedKeyMessage(), mine.name());
        return null;
    }

    private static void draw(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        Component text = hint(mc);
        if (text == null) return;
        List<FormattedCharSequence> lines = mc.font.split(text, WIDTH);
        int w = 0;
        for (FormattedCharSequence l : lines) w = Math.max(w, mc.font.width(l));
        int x = (g.guiWidth() - w) / 2, y = 30, h = lines.size() * 10;
        g.fill(x - 4, y - 3, x + w + 4, y + h + 1, 0x90000000);
        for (FormattedCharSequence l : lines) {
            g.text(mc.font, l, (g.guiWidth() - mc.font.width(l)) / 2, y, 0xFFFFE9B0, true);
            y += 10;
        }
    }

    private OnboardingHud() {}
}
