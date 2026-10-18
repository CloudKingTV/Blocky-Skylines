package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: request to open or close the planner view. */
public record PlannerTogglePayload(boolean open) implements CustomPacketPayload {
    public static final Type<PlannerTogglePayload> TYPE = new Type<>(Townstead.id("planner_toggle"));
    public static final StreamCodec<ByteBuf, PlannerTogglePayload> STREAM_CODEC =
            ByteBufCodecs.BOOL.map(PlannerTogglePayload::new, PlannerTogglePayload::open);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
