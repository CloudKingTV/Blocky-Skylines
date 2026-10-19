package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server zoning and building edits from the planner. */
public final class LandEditPayloads {
    /** Paint cells with a zone (ordinal), or clear them (zone = -1). One brush stroke per payload. */
    public record Paint(UUID cityId, int zone, List<Long> cells) implements CustomPacketPayload {
        public static final Type<Paint> TYPE = new Type<>(Townstead.id("zone_paint"));
        public static final StreamCodec<ByteBuf, Paint> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Paint::cityId, ByteBufCodecs.VAR_INT, Paint::zone,
                ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.collection(ArrayList::new, 512)), Paint::cells,
                Paint::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record BulldozeBuilding(UUID cityId, int buildingId) implements CustomPacketPayload {
        public static final Type<BulldozeBuilding> TYPE = new Type<>(Townstead.id("bulldoze_building"));
        public static final StreamCodec<ByteBuf, BulldozeBuilding> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, BulldozeBuilding::cityId, ByteBufCodecs.VAR_INT, BulldozeBuilding::buildingId,
                BulldozeBuilding::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Place a service or utility (ordinal of ServiceType) on a lot. */
    public record PlaceService(UUID cityId, int service, long cell) implements CustomPacketPayload {
        public static final Type<PlaceService> TYPE = new Type<>(Townstead.id("place_service"));
        public static final StreamCodec<ByteBuf, PlaceService> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, PlaceService::cityId, ByteBufCodecs.VAR_INT, PlaceService::service, ByteBufCodecs.VAR_LONG, PlaceService::cell,
                PlaceService::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client → server: buy the land tile at (tileX, tileZ) for the town. */
    public record BuyTile(UUID cityId, int tileX, int tileZ) implements CustomPacketPayload {
        public static final Type<BuyTile> TYPE = new Type<>(Townstead.id("buy_tile"));
        public static final StreamCodec<ByteBuf, BuyTile> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, BuyTile::cityId, ByteBufCodecs.VAR_INT, BuyTile::tileX, ByteBufCodecs.VAR_INT, BuyTile::tileZ, BuyTile::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private LandEditPayloads() {}
}
