package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.city.CitySummary;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: every town's public summary. Sent on login and whenever a town changes. */
public record CitySnapshotPayload(List<CitySummary> cities) implements CustomPacketPayload {
    public static final Type<CitySnapshotPayload> TYPE = new Type<>(Townstead.id("city_snapshot"));
    public static final StreamCodec<ByteBuf, CitySnapshotPayload> STREAM_CODEC = ByteBufCodecs
            .collection(ArrayList::new, CitySummary.STREAM_CODEC)
            .map(CitySnapshotPayload::new, p -> new ArrayList<>(p.cities()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
