package com.cloudkingtv.townstead.network;

import com.cloudkingtv.townstead.Townstead;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Money traffic: the budget panel's controls, and the Town Hall's treasury screen. Results ride on the city snapshot. */
public final class EconomyPayloads {
    public enum Action { TAX, BUDGET, TAKE_LOAN, REPAY_LOAN, BUY }

    /**
     * Client → server, from the planner: set tax {@code index} or department budget {@code index} to {@code value}, take / repay
     * loan offer {@code index}, or buy {@code value} of material {@code index} in.
     */
    public record Edit(UUID cityId, Action action, int index, int value) implements CustomPacketPayload {
        public static final Type<Edit> TYPE = new Type<>(Townstead.id("economy_edit"));
        public static final StreamCodec<ByteBuf, Edit> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Edit::cityId,
                ByteBufCodecs.VAR_INT.map(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal), Edit::action,
                ByteBufCodecs.VAR_INT, Edit::index, ByteBufCodecs.VAR_INT, Edit::value, Edit::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server → client: open the Town Hall's treasury screen. */
    public record OpenTownHall(UUID cityId, BlockPos pos) implements CustomPacketPayload {
        public static final Type<OpenTownHall> TYPE = new Type<>(Townstead.id("town_hall_open"));
        public static final StreamCodec<ByteBuf, OpenTownHall> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, OpenTownHall::cityId, BlockPos.STREAM_CODEC, OpenTownHall::pos, OpenTownHall::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client → server: take emeralds out of the treasury at the Town Hall at pos. */
    public record Withdraw(BlockPos pos, int emeralds) implements CustomPacketPayload {
        public static final Type<Withdraw> TYPE = new Type<>(Townstead.id("town_hall_withdraw"));
        public static final StreamCodec<ByteBuf, Withdraw> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Withdraw::pos, ByteBufCodecs.VAR_INT, Withdraw::emeralds, Withdraw::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client → server: put every emerald I carry into the treasury at the Town Hall at pos. */
    public record DepositAll(BlockPos pos) implements CustomPacketPayload {
        public static final Type<DepositAll> TYPE = new Type<>(Townstead.id("town_hall_deposit_all"));
        public static final StreamCodec<ByteBuf, DepositAll> STREAM_CODEC = BlockPos.STREAM_CODEC.map(DepositAll::new, DepositAll::pos);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Client → server: buy {@code count} of material {@code material} in for the storehouse, at the Town Hall at pos. */
    public record BuyAtHall(BlockPos pos, int material, int count) implements CustomPacketPayload {
        public static final Type<BuyAtHall> TYPE = new Type<>(Townstead.id("town_hall_buy"));
        public static final StreamCodec<ByteBuf, BuyAtHall> STREAM_CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, BuyAtHall::pos, ByteBufCodecs.VAR_INT, BuyAtHall::material, ByteBufCodecs.VAR_INT, BuyAtHall::count, BuyAtHall::new);

        @Override
        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private EconomyPayloads() {}
}
