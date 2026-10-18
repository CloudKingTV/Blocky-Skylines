package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.road.RoadCurve;
import com.cloudkingtv.townstead.road.RoadType;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: build a road along this centreline (already snapped by the client; the server re-checks). */
public record BuildRoadPayload(UUID cityId, RoadType roadType, RoadCurve curve) implements CustomPacketPayload {
    public static final Type<BuildRoadPayload> TYPE = new Type<>(Townstead.id("build_road"));
    static final StreamCodec<ByteBuf, RoadCurve> CURVE = new StreamCodec<>() {
        @Override
        public RoadCurve decode(ByteBuf buf) {
            return new RoadCurve(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble());
        }

        @Override
        public void encode(ByteBuf buf, RoadCurve c) {
            buf.writeDouble(c.ax()).writeDouble(c.az()).writeDouble(c.cx()).writeDouble(c.cz()).writeDouble(c.bx()).writeDouble(c.bz());
        }
    };
    public static final StreamCodec<ByteBuf, BuildRoadPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, BuildRoadPayload::cityId,
            ByteBufCodecs.idMapper(i -> RoadType.values()[Math.floorMod(i, RoadType.values().length)], RoadType::ordinal), BuildRoadPayload::roadType,
            CURVE, BuildRoadPayload::curve,
            BuildRoadPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
