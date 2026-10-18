package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.zone.LandData;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: zoning and buildings of the player's dimension. */
public record LandSnapshotPayload(LandData land) implements CustomPacketPayload {
    public static final Type<LandSnapshotPayload> TYPE = new Type<>(Townstead.id("land"));
    public static final StreamCodec<ByteBuf, LandSnapshotPayload> STREAM_CODEC =
            ByteBufCodecs.fromCodecTrusted(LandData.CODEC).map(LandSnapshotPayload::new, LandSnapshotPayload::land);

    public static LandSnapshotPayload of(LandData land) {
        return new LandSnapshotPayload(land.copy());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
