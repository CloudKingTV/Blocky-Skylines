package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.client.planner.PlannerClient;
import com.cloudkingtv.townstead.client.planner.PlannerRenderTypes;
import com.cloudkingtv.townstead.client.planner.PlannerWorldRenderer;
import com.cloudkingtv.townstead.network.CitySnapshotPayload;
import com.cloudkingtv.townstead.network.PlannerStatePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

@Mod(value = Townstead.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = Townstead.MODID, value = Dist.CLIENT)
public final class TownsteadClient {
    public TownsteadClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void keys(RegisterKeyMappingsEvent event) {
        event.registerCategory(TownsteadKeys.CATEGORY);
        event.register(TownsteadKeys.PLANNER);
    }

    @SubscribeEvent
    static void pipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PlannerRenderTypes.OVERLAY_LINES_PIPELINE);
    }

    @SubscribeEvent
    static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(CitySnapshotPayload.TYPE, (msg, ctx) -> ClientCityCache.set(msg.cities()));
        event.register(PlannerStatePayload.TYPE, (msg, ctx) -> PlannerClient.onState(msg));
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (TownsteadKeys.PLANNER.consumeClick()) {
            if (!PlannerClient.isActive() && mc.player != null) PlannerClient.requestOpen();
        }
        PlannerClient.tick(mc);
    }

    @SubscribeEvent
    static void world(SubmitCustomGeometryEvent event) {
        PlannerWorldRenderer.submit(event);
    }

    // While planning, the planner screen is the whole HUD: hide vanilla layers and the hand.
    @SubscribeEvent
    static void hideHud(RenderGuiLayerEvent.Pre event) {
        if (PlannerClient.isActive()) event.setCanceled(true);
    }

    @SubscribeEvent
    static void hideHand(RenderHandEvent event) {
        if (PlannerClient.isActive()) event.setCanceled(true);
    }

    /** A steady, slightly narrow FOV reads more like a map than a first-person view. */
    @SubscribeEvent
    static void fov(ViewportEvent.ComputeFov event) {
        if (PlannerClient.isActive()) event.setFOV(55f);
    }

    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        PlannerClient.reset();
        ClientCityCache.clear();
    }
}
