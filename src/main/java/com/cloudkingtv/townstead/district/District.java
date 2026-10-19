package com.cloudkingtv.townstead.district;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.DyeColor;

/**
 * A district: a named, coloured patch of the town's 8x8 cells, painted in the planner, with its own policies and
 * specialisation, as in Cities: Skylines.
 */
public record District(int id, String name, DyeColor color, Set<Long> cells, Set<Policy> policies, Specialization spec) {
    public static final Codec<District> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(District::id),
            Codec.STRING.fieldOf("name").forGetter(District::name),
            DyeColor.CODEC.fieldOf("color").forGetter(District::color),
            Codec.LONG.listOf().fieldOf("cells").forGetter(d -> List.copyOf(d.cells)),
            Policy.CODEC.listOf().optionalFieldOf("policies", List.of()).forGetter(d -> List.copyOf(d.policies)),
            Specialization.CODEC.optionalFieldOf("spec", Specialization.NONE).forGetter(District::spec)
    ).apply(i, (id, name, color, cells, policies, spec) -> new District(id, name, color, new LinkedHashSet<>(cells), set(policies), spec)));

    public static final StreamCodec<ByteBuf, District> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, District::id, ByteBufCodecs.STRING_UTF8, District::name, DyeColor.STREAM_CODEC, District::color,
            ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.collection(LinkedHashSet::new)), District::cells,
            ByteBufCodecs.VAR_INT.<Policy>map(o -> Policy.values()[o], Policy::ordinal).apply(ByteBufCodecs.collection(java.util.HashSet::new)),
            d -> d.policies,
            ByteBufCodecs.VAR_INT.map(o -> Specialization.values()[o], Specialization::ordinal), District::spec,
            (id, name, color, cells, policies, spec) -> new District(id, name, color, cells, set(policies), spec));

    static Set<Policy> set(java.util.Collection<Policy> policies) {
        Set<Policy> s = EnumSet.noneOf(Policy.class);
        s.addAll(policies);
        return s;
    }

    public District withCells(Set<Long> cells) { return new District(id, name, color, cells, policies, spec); }
    public District withPolicies(Set<Policy> policies) { return new District(id, name, color, cells, policies, spec); }
    public District withSpec(Specialization spec) { return new District(id, name, color, cells, policies, spec); }

    public int rgb() { return color.getTextureDiffuseColor() & 0xFFFFFF; }

    private static final String[] FIRST = {"Mill", "Oak", "Ash", "Brook", "Hay", "Elder", "Wren", "Thorn", "Kings", "Market", "Old", "Fern",
            "Bell", "Willow", "Stone", "Apple", "Rook", "Barley"};
    private static final String[] LAST = {"brook", "field", "hollow", "gate", "green", "ford", "croft", "end", "row", "stead", "side",
            "bury", "wick", "dale"};
    /** Colours handed to new districts in turn. */
    public static final DyeColor[] PALETTE = {DyeColor.ORANGE, DyeColor.LIGHT_BLUE, DyeColor.MAGENTA, DyeColor.LIME, DyeColor.YELLOW,
            DyeColor.PINK, DyeColor.CYAN, DyeColor.PURPLE, DyeColor.RED, DyeColor.GREEN};

    /** A village-sounding name, the same for the same seed. */
    public static String name(long seed) {
        int a = (int) Math.floorMod(seed * 31 + 7, (long) FIRST.length), b = (int) Math.floorMod(seed * 17 + 3, (long) LAST.length);
        return FIRST[a] + LAST[b];
    }

    public static List<District> copyAll(List<District> list) { return new ArrayList<>(list); }
}
