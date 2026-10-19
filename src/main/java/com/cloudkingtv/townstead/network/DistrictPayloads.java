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

/** Client → server district and policy edits from the planner. */
public final class DistrictPayloads {
    /** Paint cells into a district (its id), a new one (-1), or out of any (-2). */
    public record Paint(UUID cityId, int district, List<Long> cells) implements CustomPacketPayload {
        public static final Type<Paint> TYPE = new Type<>(Townstead.id("district_paint"));
        public static final StreamCodec<ByteBuf, Paint> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Paint::cityId, ByteBufCodecs.VAR_INT, Paint::district,
                ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.collection(ArrayList::new, 1024)), Paint::cells, Paint::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Turn a policy (ordinal) on or off, for a district or (district -1) the whole town. */
    public record SetPolicy(UUID cityId, int district, int policy, boolean on) implements CustomPacketPayload {
        public static final Type<SetPolicy> TYPE = new Type<>(Townstead.id("set_policy"));
        public static final StreamCodec<ByteBuf, SetPolicy> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, SetPolicy::cityId, ByteBufCodecs.VAR_INT, SetPolicy::district, ByteBufCodecs.VAR_INT, SetPolicy::policy,
                ByteBufCodecs.BOOL, SetPolicy::on, SetPolicy::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Set a district's specialisation (ordinal), or delete the district (spec -1). */
    public record SetSpec(UUID cityId, int district, int spec) implements CustomPacketPayload {
        public static final Type<SetSpec> TYPE = new Type<>(Townstead.id("set_spec"));
        public static final StreamCodec<ByteBuf, SetSpec> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, SetSpec::cityId, ByteBufCodecs.VAR_INT, SetSpec::district, ByteBufCodecs.VAR_INT, SetSpec::spec, SetSpec::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private DistrictPayloads() {}
}
