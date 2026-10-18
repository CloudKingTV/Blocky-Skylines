package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: the planner view opened (for a town) or closed. */
public record PlannerStatePayload(boolean active, UUID cityId) implements CustomPacketPayload {
    public static final UUID NO_CITY = new UUID(0, 0);
    public static final Type<PlannerStatePayload> TYPE = new Type<>(Townstead.id("planner_state"));
    public static final StreamCodec<ByteBuf, PlannerStatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, PlannerStatePayload::active, UUIDUtil.STREAM_CODEC, PlannerStatePayload::cityId,
            PlannerStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
