package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: undo (or redo) the town's last planner edit. */
public record HistoryPayload(UUID cityId, boolean redo) implements CustomPacketPayload {
    public static final Type<HistoryPayload> TYPE = new Type<>(Townstead.id("history"));
    public static final StreamCodec<ByteBuf, HistoryPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, HistoryPayload::cityId, ByteBufCodecs.BOOL, HistoryPayload::redo, HistoryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
