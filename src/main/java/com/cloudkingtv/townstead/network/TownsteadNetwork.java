package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.city.StorehouseService;
import com.cloudkingtv.townstead.building.LandEditor;
import com.cloudkingtv.townstead.history.EditHistory;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.zone.ZoneType;
import com.cloudkingtv.townstead.road.RoadEditor;
import java.util.Optional;
import java.util.UUID;
import com.cloudkingtv.townstead.city.TownActions;
import com.cloudkingtv.townstead.planner.PlannerService;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = Townstead.MODID)
public final class TownsteadNetwork {
    /** Bump whenever a payload's wire format changes. */
    private static final String PROTOCOL = "5";

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
        r.playToClient(PlannerMessagePayload.TYPE, PlannerMessagePayload.STREAM_CODEC);
        r.playToClient(RoadSnapshotPayload.TYPE, RoadSnapshotPayload.STREAM_CODEC);
        r.playToClient(StorehousePayloads.Open.TYPE, StorehousePayloads.Open.STREAM_CODEC);
        r.playToServer(BuildRoadPayload.TYPE, BuildRoadPayload.STREAM_CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) plannerCity(player, msg.cityId())
                    .ifPresent(city -> RoadEditor.build(player, city, msg.roadType(), msg.curve()));
        });
        r.playToServer(BulldozeRoadPayload.TYPE, BulldozeRoadPayload.STREAM_CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) plannerCity(player, msg.cityId())
                    .ifPresent(city -> RoadEditor.bulldoze(player, city, msg.segmentId()));
        });
        r.playToClient(LandSnapshotPayload.TYPE, LandSnapshotPayload.STREAM_CODEC);
        r.playToServer(LandEditPayloads.Paint.TYPE, LandEditPayloads.Paint.STREAM_CODEC, (msg, ctx) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            ZoneType zone = msg.zone() >= 0 && msg.zone() < ZoneType.PAINTABLE.size() ? ZoneType.PAINTABLE.get(msg.zone()) : null;
            plannerCity(player, msg.cityId()).ifPresent(city -> LandEditor.paint(player, city, zone, msg.cells()));
        });
        r.playToServer(LandEditPayloads.PlaceService.TYPE, LandEditPayloads.PlaceService.STREAM_CODEC, (msg, ctx) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (msg.service() < 0 || msg.service() >= ServiceType.values().length) return;
            plannerCity(player, msg.cityId()).ifPresent(city -> LandEditor.placeService(player, city, ServiceType.values()[msg.service()], msg.cell()));
        });
        r.playToServer(LandEditPayloads.BulldozeBuilding.TYPE, LandEditPayloads.BulldozeBuilding.STREAM_CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) plannerCity(player, msg.cityId())
                    .ifPresent(city -> LandEditor.bulldoze(player, city, msg.buildingId()));
        });
        r.playToServer(HistoryPayload.TYPE, HistoryPayload.STREAM_CODEC, (msg, ctx) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            plannerCity(player, msg.cityId()).ifPresent(city -> {
                if (msg.redo()) EditHistory.redo(player, city);
                else EditHistory.undo(player, city);
            });
        });
        r.playToServer(StorehousePayloads.DepositAll.TYPE, StorehousePayloads.DepositAll.STREAM_CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) StorehouseService.access(player, msg.pos())
                    .ifPresent(city -> StorehouseService.depositAll(player, city));
        });
        r.playToServer(StorehousePayloads.Withdraw.TYPE, StorehousePayloads.Withdraw.STREAM_CODEC, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player) StorehouseService.access(player, msg.pos())
                    .ifPresent(city -> StorehouseService.withdraw(player, city, msg.item(), msg.count()));
        });
        r.playToServer(RenameTownPayload.TYPE, RenameTownPayload.STREAM_CODEC, (msg, ctx) -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            CityManager.get(player.level().getServer()).byId(msg.cityId())
                    .ifPresent(city -> TownActions.rename(player, city, msg.name()));
        });
    }

    /** Planner edits are only accepted from the town's mayor while they're in its planner. */
    private static Optional<City> plannerCity(ServerPlayer player, UUID cityId) {
        if (!PlannerService.isPlanning(player)) return Optional.empty();
        return CityManager.get(player.level().getServer()).byId(cityId).filter(c -> c.owner().equals(player.getUUID()));
    }

    private TownsteadNetwork() {}
}
