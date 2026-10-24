package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.city.StorehouseService;
import com.cloudkingtv.townstead.building.LandEditor;
import com.cloudkingtv.townstead.economy.TownEconomy;
import com.cloudkingtv.townstead.history.EditHistory;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.zone.ZoneType;
import com.cloudkingtv.townstead.road.RoadCurve;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.city.TilePos;
import com.cloudkingtv.townstead.city.TownActions;
import com.cloudkingtv.townstead.district.DistrictEditor;
import com.cloudkingtv.townstead.district.Policy;
import com.cloudkingtv.townstead.district.Specialization;
import com.cloudkingtv.townstead.planner.PlannerService;
import com.cloudkingtv.townstead.transit.CoachLines;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@EventBusSubscriber(modid = Townstead.MODID)
public final class TownsteadNetwork {
    /** Bump whenever a payload's wire format changes. */
    private static final String PROTOCOL = "10";

    @SubscribeEvent
    static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar r = event.registrar(PROTOCOL);
        r.playToClient(CitySnapshotPayload.TYPE, CitySnapshotPayload.STREAM_CODEC);
        r.playToClient(PlannerStatePayload.TYPE, PlannerStatePayload.STREAM_CODEC);
        r.playToClient(PlannerMessagePayload.TYPE, PlannerMessagePayload.STREAM_CODEC);
        r.playToClient(RoadSnapshotPayload.TYPE, RoadSnapshotPayload.STREAM_CODEC);
        r.playToClient(StorehousePayloads.Open.TYPE, StorehousePayloads.Open.STREAM_CODEC);
        r.playToClient(LandSnapshotPayload.TYPE, LandSnapshotPayload.STREAM_CODEC);
        r.playToClient(ChirpPayload.TYPE, ChirpPayload.STREAM_CODEC);
        r.playToClient(EconomyPayloads.OpenTownHall.TYPE, EconomyPayloads.OpenTownHall.STREAM_CODEC);

        serverbound(r, PlannerTogglePayload.TYPE, PlannerTogglePayload.STREAM_CODEC, PayloadGuard.HEAVY, (player, msg) -> {
            if (msg.open()) PlannerService.enter(player);
            else PlannerService.exit(player);
        });

        // Planner edits: the town's mayor or a co-mayor, in its planner.
        serverbound(r, BuildRoadPayload.TYPE, BuildRoadPayload.STREAM_CODEC, PayloadGuard.HEAVY, (player, msg) -> {
            RoadCurve c = msg.curve();
            if (!PayloadGuard.finite(c.ax(), c.az(), c.cx(), c.cz(), c.bx(), c.bz())) return;
            plannerCity(player, msg.cityId()).ifPresent(city -> RoadEditor.build(player, city, msg.roadType(), c));
        });
        serverbound(r, BulldozeRoadPayload.TYPE, BulldozeRoadPayload.STREAM_CODEC, PayloadGuard.HEAVY, (player, msg) ->
                plannerCity(player, msg.cityId()).ifPresent(city -> RoadEditor.bulldoze(player, city, msg.segmentId())));
        serverbound(r, LandEditPayloads.Paint.TYPE, LandEditPayloads.Paint.STREAM_CODEC, PayloadGuard.HEAVY, (player, msg) -> {
            ZoneType zone = msg.zone() >= 0 && msg.zone() < ZoneType.PAINTABLE.size() ? ZoneType.PAINTABLE.get(msg.zone()) : null;
            plannerCity(player, msg.cityId()).ifPresent(city -> LandEditor.paint(player, city, zone, msg.cells()));
        });
        serverbound(r, LandEditPayloads.PlaceService.TYPE, LandEditPayloads.PlaceService.STREAM_CODEC, PayloadGuard.HEAVY, (player, msg) -> {
            if (msg.service() < 0 || msg.service() >= ServiceType.values().length) return;
            plannerCity(player, msg.cityId()).ifPresent(city -> LandEditor.placeService(player, city, ServiceType.values()[msg.service()], msg.cell()));
        });
        serverbound(r, LandEditPayloads.BulldozeBuilding.TYPE, LandEditPayloads.BulldozeBuilding.STREAM_CODEC, PayloadGuard.HEAVY, (player, msg) ->
                plannerCity(player, msg.cityId()).ifPresent(city -> LandEditor.bulldoze(player, city, msg.buildingId())));
        serverbound(r, LandEditPayloads.BuyTile.TYPE, LandEditPayloads.BuyTile.STREAM_CODEC, PayloadGuard.HEAVY, (player, msg) ->
                plannerCity(player, msg.cityId()).ifPresent(city -> TownActions.buyTile(player, city, new TilePos(msg.tileX(), msg.tileZ()))));
        serverbound(r, HistoryPayload.TYPE, HistoryPayload.STREAM_CODEC, PayloadGuard.HEAVY, (player, msg) ->
                plannerCity(player, msg.cityId()).ifPresent(city -> {
                    if (msg.redo()) EditHistory.redo(player, city);
                    else EditHistory.undo(player, city);
                }));
        serverbound(r, DistrictPayloads.Paint.TYPE, DistrictPayloads.Paint.STREAM_CODEC, PayloadGuard.HEAVY, (player, msg) ->
                plannerCity(player, msg.cityId()).ifPresent(city -> DistrictEditor.paint(player, city, msg.district(), msg.cells())));
        serverbound(r, DistrictPayloads.SetPolicy.TYPE, DistrictPayloads.SetPolicy.STREAM_CODEC, PayloadGuard.LIGHT, (player, msg) -> {
            if (msg.policy() < 0 || msg.policy() >= Policy.values().length) return;
            plannerCity(player, msg.cityId()).ifPresent(city -> DistrictEditor.setPolicy(player, city, msg.district(), Policy.values()[msg.policy()], msg.on()));
        });
        serverbound(r, DistrictPayloads.SetSpec.TYPE, DistrictPayloads.SetSpec.STREAM_CODEC, PayloadGuard.LIGHT, (player, msg) -> {
            if (msg.spec() >= Specialization.values().length) return;
            plannerCity(player, msg.cityId()).ifPresent(city -> {
                if (msg.spec() < 0) DistrictEditor.delete(player, city, msg.district());
                else DistrictEditor.setSpec(player, city, msg.district(), Specialization.values()[msg.spec()]);
            });
        });
        serverbound(r, TransitPayloads.CreateLine.TYPE, TransitPayloads.CreateLine.STREAM_CODEC, PayloadGuard.HEAVY, (player, msg) -> {
            if (msg.stops().stream().anyMatch(s -> !PayloadGuard.finite(s.t()))) return;
            plannerCity(player, msg.cityId()).ifPresent(city -> CoachLines.create(player, city, msg.stops()));
        });
        serverbound(r, TransitPayloads.RemoveLine.TYPE, TransitPayloads.RemoveLine.STREAM_CODEC, PayloadGuard.LIGHT, (player, msg) ->
                plannerCity(player, msg.cityId()).ifPresent(city -> CoachLines.remove(player, city, msg.lineId())));
        serverbound(r, EconomyPayloads.Edit.TYPE, EconomyPayloads.Edit.STREAM_CODEC, PayloadGuard.LIGHT, (player, msg) ->
                plannerCity(player, msg.cityId()).ifPresent(city -> {
                    boolean loan = msg.action() == EconomyPayloads.Action.TAKE_LOAN || msg.action() == EconomyPayloads.Action.REPAY_LOAN;
                    if (loan && !city.owner().equals(player.getUUID())) {
                        RoadEditor.notify(player, Component.translatable("economy.townstead.mayor_only"), false);
                        return;
                    }
                    switch (msg.action()) {
                        case TAX -> TownEconomy.setTax(player, city, msg.index(), msg.value());
                        case BUDGET -> TownEconomy.setBudget(player, city, msg.index(), msg.value());
                        case TAKE_LOAN -> TownEconomy.takeLoan(player, city, msg.index());
                        case REPAY_LOAN -> TownEconomy.repayLoan(player, city, msg.index());
                        case BUY -> TownEconomy.buy(player, city, msg.index(), msg.value());
                    }
                }));
        serverbound(r, RenameTownPayload.TYPE, RenameTownPayload.STREAM_CODEC, PayloadGuard.HEAVY, (player, msg) ->
                CityManager.get(player.level().getServer()).byId(msg.cityId()).ifPresent(city -> TownActions.rename(player, city, msg.name())));

        // Block screens: the player stands at the block (checked before the block is looked at, so a far-off position
        // can't load chunks), and it belongs to their town.
        serverbound(r, StorehousePayloads.DepositAll.TYPE, StorehousePayloads.DepositAll.STREAM_CODEC, PayloadGuard.LIGHT, (player, msg) ->
                StorehouseService.access(player, msg.pos()).ifPresent(city -> StorehouseService.depositAll(player, city)));
        serverbound(r, StorehousePayloads.Withdraw.TYPE, StorehousePayloads.Withdraw.STREAM_CODEC, PayloadGuard.LIGHT, (player, msg) ->
                StorehouseService.access(player, msg.pos()).ifPresent(city -> StorehouseService.withdraw(player, city, msg.item(), msg.count())));
        serverbound(r, EconomyPayloads.Withdraw.TYPE, EconomyPayloads.Withdraw.STREAM_CODEC, PayloadGuard.LIGHT, (player, msg) ->
                TownEconomy.townHall(player, msg.pos()).ifPresent(city -> TownEconomy.withdraw(player, city, msg.emeralds())));
        serverbound(r, EconomyPayloads.BuyAtHall.TYPE, EconomyPayloads.BuyAtHall.STREAM_CODEC, PayloadGuard.LIGHT, (player, msg) ->
                TownEconomy.townHall(player, msg.pos()).filter(city -> city.isMayor(player.getUUID()))
                        .ifPresent(city -> TownEconomy.buy(player, city, msg.material(), msg.count())));
        serverbound(r, EconomyPayloads.DepositAll.TYPE, EconomyPayloads.DepositAll.STREAM_CODEC, PayloadGuard.LIGHT, (player, msg) ->
                TownEconomy.townHall(player, msg.pos()).ifPresent(city -> TownEconomy.depositAll(player, city, msg.pos())));
    }

    /** Registers a client → server payload whose handler runs for a server player with tokens to spend. */
    private static <T extends CustomPacketPayload> void serverbound(PayloadRegistrar r, CustomPacketPayload.Type<T> type,
                                                                     StreamCodec<? super RegistryFriendlyByteBuf, T> codec, int cost,
                                                                     BiConsumer<ServerPlayer, T> handler) {
        r.playToServer(type, codec, (msg, ctx) -> {
            if (ctx.player() instanceof ServerPlayer player && PayloadGuard.allow(player, cost)) handler.accept(player, msg);
        });
    }

    /** Planner edits are only accepted from the town's mayor or a co-mayor while they're in its planner. */
    private static Optional<City> plannerCity(ServerPlayer player, UUID cityId) {
        if (!PlannerService.isPlanning(player)) return Optional.empty();
        return CityManager.get(player.level().getServer()).byId(cityId).filter(c -> c.isMayor(player.getUUID()));
    }

    private TownsteadNetwork() {}
}
