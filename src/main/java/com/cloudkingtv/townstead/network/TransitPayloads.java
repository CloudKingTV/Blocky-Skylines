package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server coach line edits from the planner's transport tool. */
public final class TransitPayloads {
    /** A stop as the client picked it: a road segment and the curve parameter along it. */
    public record StopPick(int segment, double t) {
        static final StreamCodec<ByteBuf, StopPick> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, StopPick::segment, ByteBufCodecs.DOUBLE, StopPick::t, StopPick::new);
    }

    /** Draw a new line through these stops, in order (it loops back from the last to the first). */
    public record CreateLine(UUID cityId, List<StopPick> stops) implements CustomPacketPayload {
        public static final Type<CreateLine> TYPE = new Type<>(Townstead.id("create_line"));
        public static final StreamCodec<ByteBuf, CreateLine> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, CreateLine::cityId, StopPick.STREAM_CODEC.apply(ByteBufCodecs.collection(ArrayList::new, 32)), CreateLine::stops,
                CreateLine::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record RemoveLine(UUID cityId, int lineId) implements CustomPacketPayload {
        public static final Type<RemoveLine> TYPE = new Type<>(Townstead.id("remove_line"));
        public static final StreamCodec<ByteBuf, RemoveLine> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, RemoveLine::cityId, ByteBufCodecs.VAR_INT, RemoveLine::lineId, RemoveLine::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private TransitPayloads() {}
}
