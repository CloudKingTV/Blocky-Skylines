package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Town Storehouse traffic. The stock itself rides on the city snapshot. */
public final class StorehousePayloads {
    /** Server → client: open the storehouse screen for this town. */
    public record Open(UUID cityId, BlockPos pos) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(Townstead.id("storehouse_open"));
        public static final StreamCodec<ByteBuf, Open> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Open::cityId, BlockPos.STREAM_CODEC, Open::pos, Open::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client → server: put every building material from my inventory into the storehouse at pos. */
    public record DepositAll(BlockPos pos) implements CustomPacketPayload {
        public static final Type<DepositAll> TYPE = new Type<>(Townstead.id("storehouse_deposit_all"));
        public static final StreamCodec<ByteBuf, DepositAll> STREAM_CODEC = BlockPos.STREAM_CODEC.map(DepositAll::new, DepositAll::pos);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client → server: take up to {@code count} of an item out of the storehouse at pos. */
    public record Withdraw(BlockPos pos, Identifier item, int count) implements CustomPacketPayload {
        public static final Type<Withdraw> TYPE = new Type<>(Townstead.id("storehouse_withdraw"));
        public static final StreamCodec<ByteBuf, Withdraw> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Withdraw::pos, Identifier.STREAM_CODEC, Withdraw::item, ByteBufCodecs.VAR_INT, Withdraw::count,
                Withdraw::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private StorehousePayloads() {}
}
