package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: remove one road segment. */
public record BulldozeRoadPayload(UUID cityId, int segmentId) implements CustomPacketPayload {
    public static final Type<BulldozeRoadPayload> TYPE = new Type<>(Townstead.id("bulldoze_road"));
    public static final StreamCodec<ByteBuf, BulldozeRoadPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, BulldozeRoadPayload::cityId, ByteBufCodecs.VAR_INT, BulldozeRoadPayload::segmentId,
            BulldozeRoadPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
