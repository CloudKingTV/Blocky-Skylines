package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: the mayor renamed their town. */
public record RenameTownPayload(UUID cityId, String name) implements CustomPacketPayload {
    public static final Type<RenameTownPayload> TYPE = new Type<>(Townstead.id("rename_town"));
    public static final StreamCodec<ByteBuf, RenameTownPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, RenameTownPayload::cityId, ByteBufCodecs.stringUtf8(64), RenameTownPayload::name,
            RenameTownPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
