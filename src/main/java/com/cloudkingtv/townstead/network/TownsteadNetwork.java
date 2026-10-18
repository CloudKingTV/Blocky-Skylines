package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.planner.PlannerService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = Townstead.MODID)
public final class TownsteadNetwork {
    /** Bump whenever a payload's wire format changes. */
    private static final String PROTOCOL = "1";

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(PROTOCOL);
        r.playToClient(CitySnapshotPayload.TYPE, CitySnapshotPayload.STREAM_CODEC);
        r.playToClient(PlannerStatePayload.TYPE, PlannerStatePayload.STREAM_CODEC);
        r.playToServer(PlannerTogglePayload.TYPE, PlannerTogglePayload.STREAM_CODEC, (msg, ctx) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (msg.open()) PlannerService.enter(player);
            else PlannerService.exit(player);
        });
    }

    private TownsteadNetwork() {}
}
