package com.cloudkingtv.townstead.road;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The three road sizes, each a cross-section of village blocks. Paths are dirt path (what villagers walk on),
 * kerbs are stone bricks, and the avenue's median is a strip of grass with hedges and lamps.
 */
public enum RoadType implements StringRepresentable {
    /** 3 wide: a plain village lane. */
    LANE("lane", Items.DIRT_PATH, "CCC", 0, 0),
    /** 5 wide: a lane with stone-brick walks and lamp posts on alternating sides. */
    STREET("street", Items.STONE_BRICKS, "SCCCS", 16, 0),
    /** 9 wide: two 3-wide carriageways split by a hedged median with lamps. */
    AVENUE("avenue", Items.OAK_LEAVES, "SCCCMCCCS", 16, 4);

    public static final Codec<RoadType> CODEC = StringRepresentable.fromEnum(RoadType::values);

    /** What a column of the cross-section is. Higher rank wins where two roads overlap (junctions). */
    public enum Part {
        SIDEWALK(1), MEDIAN(2), CARRIAGEWAY(3);

        public final int rank;

        Part(int rank) { this.rank = rank; }
    }

    private final String key;
    private final Item icon;
    private final Part[] parts;
    /** Arc-length spacing of lamp posts, 0 for none. */
    public final int lampSpacing;
    /** Arc-length spacing of median hedges, 0 for none. */
    public final int hedgeSpacing;

    RoadType(String key, Item icon, String profile, int lampSpacing, int hedgeSpacing) {
        this.key = key;
        this.icon = icon;
        this.parts = profile.chars().mapToObj(c -> switch (c) {
            case 'C' -> Part.CARRIAGEWAY;
            case 'M' -> Part.MEDIAN;
            default -> Part.SIDEWALK;
        }).toArray(Part[]::new);
        this.lampSpacing = lampSpacing;
        this.hedgeSpacing = hedgeSpacing;
    }

    public int width() { return parts.length; }
    public double halfWidth() { return parts.length / 2.0; }
    public Part part(int column) { return parts[column]; }
    /** Built on demand: item stacks can't be made while registries load (networking touches this enum early). */
    public ItemStack icon() { return new ItemStack(icon); }
    public Component displayName() { return Component.translatable("road.townstead." + key); }

    @Override
    public String getSerializedName() { return key; }

    public static BlockState surface(Part part) {
        return switch (part) {
            case CARRIAGEWAY -> Blocks.DIRT_PATH.defaultBlockState();
            case MEDIAN -> Blocks.GRASS_BLOCK.defaultBlockState();
            case SIDEWALK -> Blocks.STONE_BRICKS.defaultBlockState();
        };
    }

    /** What a column costs: sidewalks are paid in cobblestone (the crew dresses it), the rest in earth. */
    public static Material material(Part part) {
        return part == Part.SIDEWALK ? Material.COBBLESTONE : Material.DIRT;
    }
}
