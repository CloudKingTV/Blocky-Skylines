package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.road.RoadNetwork;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: the whole road graph of the player's dimension (town-sized, so a full copy is fine). */
public record RoadSnapshotPayload(RoadNetwork network) implements CustomPacketPayload {
    public static final Type<RoadSnapshotPayload> TYPE = new Type<>(Townstead.id("roads"));
    public static final StreamCodec<ByteBuf, RoadSnapshotPayload> STREAM_CODEC =
            ByteBufCodecs.fromCodecTrusted(RoadNetwork.CODEC).map(RoadSnapshotPayload::new, RoadSnapshotPayload::network);

    public static RoadSnapshotPayload of(RoadNetwork network) {
        return new RoadSnapshotPayload(network.copy());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
