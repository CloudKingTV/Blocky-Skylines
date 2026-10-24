package com.cloudkingtv.townstead;

import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.planner.PlannerReturn;
import com.cloudkingtv.townstead.planner.PlannerService;
import com.cloudkingtv.townstead.registry.TownsteadRegistry;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.network.LandSnapshotPayload;
import com.cloudkingtv.townstead.zone.LandData;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import com.cloudkingtv.townstead.history.EditHistory;

@EventBusSubscriber(modid = Townstead.MODID)
public final class TownsteadEvents {
    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Left mid-plan last session: put the body back where it was.
        PlannerReturn back = player.getData(TownsteadRegistry.PLANNER_RETURN);
        if (back.active()) PlannerService.restore(player, back);
        // A prepared world's town with no mayor yet goes to the first player who has none.
        CityManager cities = CityManager.get(player.level().getServer());
        if (cities.runBy(player.getUUID()).isEmpty()) {
            cities.all().stream().filter(com.cloudkingtv.townstead.city.City::unclaimed).findFirst().ifPresent(city -> {
                cities.setOwner(player.level().getServer(), city, player.getUUID(), player.getName().getString());
                player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.townstead.claimed", city.name())
                        .withStyle(net.minecraft.ChatFormatting.GOLD));
            });
        }
        PacketDistributor.sendToPlayer(player, cities.snapshot());
        RoadEditor.syncTo(player);
        PacketDistributor.sendToPlayer(player, LandSnapshotPayload.of(LandData.get(player.level())));
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        com.cloudkingtv.townstead.network.PayloadGuard.forget(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player && PlannerService.isPlanning(player)) {
            PlannerService.restore(player, player.getData(TownsteadRegistry.PLANNER_RETURN));
        }
    }

    @SubscribeEvent
    static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        PlannerService.exit(player);
        RoadEditor.syncTo(player);
        PacketDistributor.sendToPlayer(player, LandSnapshotPayload.of(LandData.get(player.level())));
    }

    // The planner body is a camera: it must not touch the world.
    @SubscribeEvent
    static void noBreak(BreakBlockEvent event) {
        if (event.getPlayer() instanceof ServerPlayer p && PlannerService.isPlanning(p)) event.setCanceled(true);
    }

    @SubscribeEvent
    static void noUse(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer p && PlannerService.isPlanning(p)) event.setCanceled(true);
    }

    @SubscribeEvent
    static void noAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && PlannerService.isPlanning(p)) event.setCanceled(true);
    }

    /** Undo history is per session; an integrated server can restart inside the same JVM. */
    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        EditHistory.clear();
    }

    private TownsteadEvents() {}
}
