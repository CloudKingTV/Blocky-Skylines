package com.cloudkingtv.townstead.road;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** A road end or junction. x/z are block-space (usually block centres); y is the road surface block. */
public record RoadNode(int id, double x, int y, double z) {
    public static final Codec<RoadNode> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(RoadNode::id),
            Codec.DOUBLE.fieldOf("x").forGetter(RoadNode::x),
            Codec.INT.fieldOf("y").forGetter(RoadNode::y),
            Codec.DOUBLE.fieldOf("z").forGetter(RoadNode::z)
    ).apply(i, RoadNode::new));

    public double distance(double px, double pz) {
        return Math.sqrt((px - x) * (px - x) + (pz - z) * (pz - z));
    }
}
