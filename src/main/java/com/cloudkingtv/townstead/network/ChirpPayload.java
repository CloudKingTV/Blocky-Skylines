package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server → client: a resident's post about the town (see {@link com.cloudkingtv.townstead.progress.Chirps}). */
public record ChirpPayload(UUID cityId, String author, Component text, BlockPos at, String event) implements CustomPacketPayload {
    public static final Type<ChirpPayload> TYPE = new Type<>(Townstead.id("chirp"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ChirpPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC.cast(), ChirpPayload::cityId, ByteBufCodecs.STRING_UTF8.cast(), ChirpPayload::author,
            ComponentSerialization.TRUSTED_STREAM_CODEC, ChirpPayload::text, BlockPos.STREAM_CODEC.cast(), ChirpPayload::at,
            ByteBufCodecs.STRING_UTF8.cast(), ChirpPayload::event, ChirpPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
