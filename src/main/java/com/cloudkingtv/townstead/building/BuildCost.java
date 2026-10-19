package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.road.Material;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jspecify.annotations.Nullable;

/** What one placed block costs from the storehouse: one unit of a material, or nothing (flowers, paths, scaffolding). */
public final class BuildCost {
    public static @Nullable Material of(BlockState s) {
        if (s.isAir() || s.is(Blocks.SCAFFOLDING) || s.is(Blocks.DIRT_PATH) || s.is(Blocks.GRASS_BLOCK) || s.is(BlockTags.FLOWERS)) return null;
        if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) return null;
        if (s.is(BlockTags.WOODEN_STAIRS) || s.is(BlockTags.WOODEN_SLABS) || s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.WOODEN_TRAPDOORS)) {
            return Material.PLANKS;
        }
        if (s.is(Blocks.COBBLESTONE_STAIRS) || s.is(Blocks.COBBLESTONE_SLAB) || s.is(Blocks.COBBLESTONE_WALL)) return Material.COBBLESTONE;
        if (s.is(Blocks.STONE_BRICK_STAIRS) || s.is(Blocks.STONE_BRICK_SLAB) || s.is(Blocks.STONE_BRICK_WALL)) return Material.STONE_BRICKS;
        if (s.is(Blocks.SMOOTH_SANDSTONE_STAIRS) || s.is(Blocks.SMOOTH_SANDSTONE_SLAB) || s.is(Blocks.SANDSTONE_STAIRS)
                || s.is(Blocks.SANDSTONE_SLAB) || s.is(Blocks.SANDSTONE_WALL)) return Material.SANDSTONE;
        if (s.is(Blocks.BOOKSHELF) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WOODEN_PRESSURE_PLATES) || s.is(Blocks.LADDER)) return Material.PLANKS;
        if (s.getFluidState().isSource() && s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) return null;
        if (s.getBlock() instanceof net.minecraft.world.level.block.FlowerPotBlock) return Material.BRICKS;
        if (s.is(Blocks.TARGET)) return Material.HAY;
        if (s.is(Blocks.BELL) || s.is(Blocks.ANVIL)) return Material.LANTERNS;
        if (s.is(Blocks.WATER_CAULDRON)) return Material.WORKSTATIONS;
        // Shapes of a material (stairs, slabs, walls) cost one of the material, judged by name.
        String id = s.getBlock().builtInRegistryHolder().key().identifier().getPath();
        if (id.contains("wool") || id.contains("carpet") || id.contains("banner")) return Material.WOOL;
        if (id.startsWith("brick_") || id.startsWith("mud_brick")) return Material.BRICKS;
        if (id.contains("stone_brick")) return Material.STONE_BRICKS;
        if (id.contains("sandstone")) return Material.SANDSTONE;
        if (id.contains("cobblestone")) return Material.COBBLESTONE;
        if (s.is(Blocks.CAMPFIRE)) return Material.LOGS;
        if (s.is(Blocks.WALL_TORCH)) return Material.LANTERNS;
        for (Material m : Material.values()) if (m.matches(s.getBlock().asItem())) return m;
        return null;
    }

    private BuildCost() {}
}
