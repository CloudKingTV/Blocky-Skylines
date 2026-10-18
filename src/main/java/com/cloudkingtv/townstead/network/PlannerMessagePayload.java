package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: the result of a planner action, shown above the toolbar. */
public record PlannerMessagePayload(Component message, boolean ok) implements CustomPacketPayload {
    public static final Type<PlannerMessagePayload> TYPE = new Type<>(Townstead.id("planner_message"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlannerMessagePayload> STREAM_CODEC = StreamCodec.composite(
            ComponentSerialization.TRUSTED_STREAM_CODEC, PlannerMessagePayload::message, ByteBufCodecs.BOOL, PlannerMessagePayload::ok,
            PlannerMessagePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
