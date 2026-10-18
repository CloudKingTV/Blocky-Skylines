package com.cloudkingtv.townstead.city;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/** A 64x64-block land tile (4x4 chunks, 8x8 zone cells), aligned to world coordinates. */
public record TilePos(int x, int z) {
    public static final int SIZE = 64;
    public static final int CELL = 8;

    public static final Codec<TilePos> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("x").forGetter(TilePos::x),
            Codec.INT.fieldOf("z").forGetter(TilePos::z)
    ).apply(i, TilePos::new));
    public static final StreamCodec<ByteBuf, TilePos> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TilePos::x, ByteBufCodecs.VAR_INT, TilePos::z, TilePos::new);

    public static TilePos ofBlock(int blockX, int blockZ) {
        return new TilePos(Math.floorDiv(blockX, SIZE), Math.floorDiv(blockZ, SIZE));
    }

    public static TilePos of(BlockPos pos) {
        return ofBlock(pos.getX(), pos.getZ());
    }

    public int minBlockX() { return x * SIZE; }
    public int minBlockZ() { return z * SIZE; }

    public TilePos offset(int dx, int dz) {
        return new TilePos(x + dx, z + dz);
    }

    public boolean isAdjacentTo(TilePos o) {
        return Math.abs(o.x - x) + Math.abs(o.z - z) == 1;
    }
}
