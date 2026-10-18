package com.cloudkingtv.townstead.building;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Block sets for the five vanilla village styles. A building takes the palette of the biome its lot stands in,
 * so a town that spreads from meadow into desert changes style at the border, like real villages do.
 *
 * @param walls     plank-type wall blocks (one is picked per building)
 * @param post      corner posts and beams (a log where the style has one)
 * @param floor     interior floors
 * @param flatRoof  desert towns build flat roofs with a parapet instead of gables
 * @param stone     podiums, office walls and parapets
 * @param trim      a band between storeys
 * @param plants    yard plants (empty: none)
 */
public record Palette(String key, List<Block> walls, Block post, Block floor, Block roofStairs, Block roofSlab, boolean flatRoof,
                      Block foundation, Block stone, Block stoneSlab, Block trim, Block door, Block fence, List<Block> plants,
                      boolean lanterns) {
    public static final Palette PLAINS = new Palette("plains", List.of(Blocks.OAK_PLANKS, Blocks.BIRCH_PLANKS, Blocks.OAK_PLANKS),
            Blocks.OAK_LOG, Blocks.SPRUCE_PLANKS, Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB, false, Blocks.COBBLESTONE,
            Blocks.STONE_BRICKS, Blocks.STONE_BRICK_SLAB, Blocks.STRIPPED_OAK_LOG, Blocks.OAK_DOOR, Blocks.OAK_FENCE,
            List.of(Blocks.POPPY, Blocks.DANDELION, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER), false);
    public static final Palette DESERT = new Palette("desert", List.of(Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE),
            Blocks.CUT_SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB, true,
            Blocks.SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SMOOTH_SANDSTONE_SLAB, Blocks.DYED_TERRACOTTA.pick(DyeColor.ORANGE),
            Blocks.JUNGLE_DOOR, Blocks.JUNGLE_FENCE, List.of(Blocks.DEAD_BUSH, Blocks.POTTED_CACTUS), false);
    public static final Palette SAVANNA = new Palette("savanna", List.of(Blocks.ACACIA_PLANKS),
            Blocks.ACACIA_LOG, Blocks.ACACIA_PLANKS, Blocks.ACACIA_STAIRS, Blocks.ACACIA_SLAB, false, Blocks.COBBLESTONE,
            Blocks.STONE_BRICKS, Blocks.STONE_BRICK_SLAB, Blocks.DYED_TERRACOTTA.pick(DyeColor.ORANGE), Blocks.ACACIA_DOOR,
            Blocks.ACACIA_FENCE, List.of(Blocks.DANDELION, Blocks.POPPY), false);
    public static final Palette TAIGA = new Palette("taiga", List.of(Blocks.SPRUCE_PLANKS),
            Blocks.SPRUCE_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, false, Blocks.MOSSY_COBBLESTONE,
            Blocks.COBBLESTONE, Blocks.COBBLESTONE_SLAB, Blocks.STRIPPED_SPRUCE_LOG, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE,
            List.of(Blocks.FERN, Blocks.POPPY, Blocks.FERN), true);
    public static final Palette SNOWY = new Palette("snowy", List.of(Blocks.SPRUCE_PLANKS),
            Blocks.STRIPPED_SPRUCE_LOG, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, false, Blocks.STONE_BRICKS,
            Blocks.STONE_BRICKS, Blocks.STONE_BRICK_SLAB, Blocks.STONE_BRICKS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE,
            List.of(), true);

    public static final List<Palette> ALL = List.of(PLAINS, DESERT, SAVANNA, TAIGA, SNOWY);

    public static Palette of(String key) {
        for (Palette p : ALL) if (p.key.equals(key)) return p;
        return PLAINS;
    }

    /** The village style of the biome at a position (plains when the biome has no village type). */
    public static String styleAt(net.minecraft.world.level.LevelReader level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        if (biome.is(BiomeTags.HAS_VILLAGE_DESERT)) return DESERT.key;
        if (biome.is(BiomeTags.HAS_VILLAGE_SAVANNA)) return SAVANNA.key;
        if (biome.is(BiomeTags.HAS_VILLAGE_SNOWY)) return SNOWY.key;
        if (biome.is(BiomeTags.HAS_VILLAGE_TAIGA)) return TAIGA.key;
        return PLAINS.key;
    }
}
