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
    /** {@code marker}: the post's lowest block; {@code bench}: the bench beside it (the marker itself if there was no room). */
    public record Stop(int segment, double t, BlockPos marker, BlockPos bench) {
        static final Codec<Stop> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("segment").forGetter(Stop::segment),
                Codec.DOUBLE.fieldOf("t").forGetter(Stop::t),
                BlockPos.CODEC.fieldOf("marker").forGetter(Stop::marker),
                BlockPos.CODEC.optionalFieldOf("bench").forGetter(s -> java.util.Optional.of(s.bench))
        ).apply(i, (seg, t, marker, bench) -> new Stop(seg, t, marker, bench.orElse(marker))));
        static final StreamCodec<ByteBuf, Stop> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Stop::segment, ByteBufCodecs.DOUBLE, Stop::t, BlockPos.STREAM_CODEC, Stop::marker,
                BlockPos.STREAM_CODEC, Stop::bench, Stop::new);
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

    /** Where stop i meets the road, as the router takes it (null if its road is gone). */
    public com.cloudkingtv.townstead.road.RoadRouter.@org.jspecify.annotations.Nullable Access access(
            com.cloudkingtv.townstead.road.RoadRouter router, int i) {
        Stop s = stops.get(i);
        return router.stop(s.segment(), s.t(), s.marker());
    }

    public int rgb() {
        return color.getTextureDiffuseColor() & 0xFFFFFF;
    }
}
