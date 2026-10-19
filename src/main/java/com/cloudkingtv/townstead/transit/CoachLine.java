package com.cloudkingtv.townstead.transit;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;

/**
 * A coach line, the village stand-in for Cities: Skylines' bus lines: a loop of stops on the town's roads that
 * horse-drawn coaches run round, from the first stop to the last and back to the first. Each stop is a real
 * signpost and bench by the road ({@code marker} is the post).
 */
public record CoachLine(int id, String name, DyeColor color, List<Stop> stops) {
    public record Stop(int segment, double t, BlockPos marker) {
        static final Codec<Stop> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("segment").forGetter(Stop::segment),
                Codec.DOUBLE.fieldOf("t").forGetter(Stop::t),
                BlockPos.CODEC.fieldOf("marker").forGetter(Stop::marker)
        ).apply(i, Stop::new));
        static final StreamCodec<ByteBuf, Stop> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Stop::segment, ByteBufCodecs.DOUBLE, Stop::t, BlockPos.STREAM_CODEC, Stop::marker, Stop::new);
    }

    public static final Codec<CoachLine> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(CoachLine::id),
            Codec.STRING.fieldOf("name").forGetter(CoachLine::name),
            DyeColor.CODEC.fieldOf("color").forGetter(CoachLine::color),
            Stop.CODEC.listOf().fieldOf("stops").forGetter(CoachLine::stops)
    ).apply(i, CoachLine::new));

    public static final StreamCodec<ByteBuf, CoachLine> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, CoachLine::id, ByteBufCodecs.STRING_UTF8, CoachLine::name, DyeColor.STREAM_CODEC, CoachLine::color,
            ByteBufCodecs.collection(ArrayList::new, Stop.STREAM_CODEC), CoachLine::stops, CoachLine::new);

    /** Colours handed to new lines in turn. */
    public static final DyeColor[] PALETTE = {DyeColor.RED, DyeColor.BLUE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.PURPLE,
            DyeColor.ORANGE, DyeColor.CYAN, DyeColor.MAGENTA};

    /** A stop's segment and place, as the router takes them. */
    public com.cloudkingtv.townstead.road.RoadRouter.Access access(int i) {
        Stop s = stops.get(i);
        return new com.cloudkingtv.townstead.road.RoadRouter.Access(s.segment(), s.t());
    }

    public int rgb() {
        return color.getTextureDiffuseColor() & 0xFFFFFF;
    }
}
