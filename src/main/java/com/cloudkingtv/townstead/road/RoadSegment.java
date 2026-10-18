package com.cloudkingtv.townstead.road;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;

/**
 * A built piece of road between two nodes. {@code heights} holds the graded surface y at evenly spaced curve
 * parameters (index i is t = i / (n - 1)), so the road's blocks can be recomputed without reading the world.
 * {@code decorations} are the lamps and hedges actually placed, kept so bulldozing can remove exactly those.
 */
public record RoadSegment(int id, UUID city, RoadType type, int a, int b, RoadCurve curve, int[] heights,
                          long placedAt, List<Decoration> decorations) {
    static final Codec<RoadCurve> CURVE_CODEC = Codec.DOUBLE.listOf(6, 6).xmap(
            l -> new RoadCurve(l.get(0), l.get(1), l.get(2), l.get(3), l.get(4), l.get(5)),
            c -> List.of(c.ax(), c.az(), c.cx(), c.cz(), c.bx(), c.bz()));

    public static final Codec<RoadSegment> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(RoadSegment::id),
            UUIDUtil.CODEC.fieldOf("city").forGetter(RoadSegment::city),
            RoadType.CODEC.fieldOf("type").forGetter(RoadSegment::type),
            Codec.INT.fieldOf("a").forGetter(RoadSegment::a),
            Codec.INT.fieldOf("b").forGetter(RoadSegment::b),
            CURVE_CODEC.fieldOf("curve").forGetter(RoadSegment::curve),
            Codec.INT_STREAM.xmap(IntStream::toArray, Arrays::stream).fieldOf("heights").forGetter(RoadSegment::heights),
            Codec.LONG.optionalFieldOf("placed", 0L).forGetter(RoadSegment::placedAt),
            Decoration.CODEC.listOf().optionalFieldOf("decorations", List.of()).forGetter(RoadSegment::decorations)
    ).apply(i, RoadSegment::new));

    /** Graded surface y at curve parameter t. */
    public int heightAt(double t) {
        return heightAt(heights, t);
    }

    static int heightAt(int[] heights, double t) {
        if (heights.length == 1) return heights[0];
        double f = Mth.clamp(t, 0, 1) * (heights.length - 1);
        int i = Math.min((int) f, heights.length - 2);
        return (int) Math.round(Mth.lerp(f - i, heights[i], heights[i + 1]));
    }

    public RoadSegment withDecorations(List<Decoration> decorations) {
        return new RoadSegment(id, city, type, a, b, curve, heights, placedAt, List.copyOf(decorations));
    }

    public enum DecorationKind implements StringRepresentable {
        LAMP("lamp"), HEDGE("hedge");

        static final Codec<DecorationKind> CODEC = StringRepresentable.fromEnum(DecorationKind::values);
        private final String key;

        DecorationKind(String key) { this.key = key; }

        @Override
        public String getSerializedName() { return key; }
    }

    /** {@code pos} is the first block above the road surface. */
    public record Decoration(BlockPos pos, DecorationKind kind) {
        static final Codec<Decoration> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockPos.CODEC.fieldOf("pos").forGetter(Decoration::pos),
                DecorationKind.CODEC.fieldOf("kind").forGetter(Decoration::kind)
        ).apply(i, Decoration::new));
    }
}
