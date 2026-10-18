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
        if (s.is(Blocks.STONE_BRICK_STAIRS) || s.is(Blocks.STONE_BRICK_SLAB)) return Material.STONE_BRICKS;
        if (s.is(Blocks.CAMPFIRE)) return Material.LOGS;
        if (s.is(Blocks.WALL_TORCH)) return Material.LANTERNS;
        for (Material m : Material.values()) if (m.matches(s.getBlock().asItem())) return m;
        return null;
    }

    private BuildCost() {}
}
