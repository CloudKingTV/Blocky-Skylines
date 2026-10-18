package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Procedural village buildings, in the vocabulary of vanilla plains villages: cobblestone footings, log corner
 * posts, plank walls, glass-pane windows, stair-and-slab gable roofs, lanterns and flowers. Homes, shops (a
 * workstation behind the shop window and a striped wool awning) and workshops (cobblestone walls, a flat roof with
 * a smoking chimney, hay and barrels in the yard).
 *
 * The plan is deterministic from the building record, so nothing but a progress counter is saved.
 */
public final class BuildingGenerator {
    /** One construction step; a door's upper half rides along with its lower half. */
    public record Step(BlockPos pos, BlockState state) {}

    public record Plan(List<Step> steps, BlockPos min, BlockPos max, int households, int jobs, String kind, List<BlockPos> scaffold) {
        /** Every position the plan places a block at (door tops included). */
        public java.util.Set<BlockPos> positions() {
            java.util.Set<BlockPos> out = new java.util.HashSet<>();
            for (Step s : steps) {
                out.add(s.pos());
                if (s.state().getBlock() instanceof DoorBlock) out.add(s.pos().above());
            }
            return out;
        }
    }

    /** Cell-local frame: lx along the road (left to right seen from the road), lz from the road edge backwards. */
    static final class Frame {
        final Direction front, back, right, left;
        final int cellX, cellZ, baseY;
        /** Deepest a building may be: the lot minus the back row and any road rows at the front. */
        final int maxDepth;

        Frame(Building b) {
            front = b.front;
            back = front.getOpposite();
            right = front.getCounterClockWise(); // the right hand of someone on the road looking at the house
            left = right.getOpposite();
            cellX = b.cellX;
            cellZ = b.cellZ;
            baseY = b.baseY;
            maxDepth = ZoneRules.CELL - 1 - b.setback;
        }

        BlockPos at(int lx, int y, int lz) {
            double cx = cellX * ZoneRules.CELL + 4, cz = cellZ * ZoneRules.CELL + 4;
            double x = cx + right.getStepX() * (lx - 3.5) + back.getStepX() * (lz - 3.5);
            double z = cz + right.getStepZ() * (lx - 3.5) + back.getStepZ() * (lz - 3.5);
            return new BlockPos((int) Math.floor(x), baseY + y, (int) Math.floor(z));
        }
    }

    /** Builder over the house footprint: hx across, hz from the front wall back, y up from the floor. */
    private static final class Builder {
        final Frame f;
        final int ox, oz, w, d;
        final List<Step> steps = new ArrayList<>();

        Builder(Frame f, int w, int d) {
            this.f = f;
            this.w = w;
            this.d = Math.min(d, f.maxDepth);
            this.ox = (ZoneRules.CELL - w) / 2;
            this.oz = ZoneRules.CELL - d - 1;
        }

        BlockPos pos(int hx, int y, int hz) { return f.at(ox + hx, y, oz + hz); }
        void put(int hx, int y, int hz, BlockState s) { steps.add(new Step(pos(hx, y, hz), s)); }
        void putCell(int lx, int y, int lz, BlockState s) { steps.add(new Step(f.at(lx, y, lz), s)); }
        boolean corner(int hx, int hz) { return (hx == 0 || hx == w - 1) && (hz == 0 || hz == d - 1); }
        boolean edge(int hx, int hz) { return hx == 0 || hx == w - 1 || hz == 0 || hz == d - 1; }

        /** Perimeter positions in walking order, so walls rise around the house rather than row by row. */
        List<int[]> ring() {
            List<int[]> out = new ArrayList<>();
            for (int x = 0; x < w; x++) out.add(new int[] {x, 0});
            for (int z = 1; z < d; z++) out.add(new int[] {w - 1, z});
            for (int x = w - 2; x >= 0; x--) out.add(new int[] {x, d - 1});
            for (int z = d - 2; z >= 1; z--) out.add(new int[] {0, z});
            return out;
        }

        BlockState stairs(Block block, Direction facing) {
            return block.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, Half.BOTTOM);
        }

        /** Gable roof, ridge across the width, one block of eave all round. */
        void gableRoof(int roofBase, Block stairs, Block slab, BlockState gable) {
            for (int k = 0; ; k++) {
                int fz = -1 + k, bz = d - k;
                if (fz > bz) break;
                int y = roofBase + k;
                for (int hx = -1; hx <= w; hx++) {
                    if (fz == bz) put(hx, y, fz, slab.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                    else {
                        put(hx, y, fz, stairs(stairs, f.back));
                        put(hx, y, bz, stairs(stairs, f.front));
                    }
                }
                for (int hz = k; hz <= d - 1 - k; hz++) {
                    put(0, y, hz, gable);
                    put(w - 1, y, hz, gable);
                }
            }
        }

        void door(int hx, Block door, int y) {
            BlockState lower = door.defaultBlockState().setValue(DoorBlock.FACING, f.back).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
            put(hx, y, 0, lower);
        }

        /** A dirt path from the door to the road edge of the cell. */
        void frontPath(int hx) {
            for (int lz = 0; lz < oz; lz++) putCell(ox + hx, -1, lz, Blocks.DIRT_PATH.defaultBlockState());
        }
    }

    private static BlockState facing(BlockState s, Direction d) {
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return s.setValue(BlockStateProperties.HORIZONTAL_FACING, d);
        if (s.hasProperty(BlockStateProperties.FACING)) return s.setValue(BlockStateProperties.FACING, d);
        return s;
    }

    private static BlockState log(Block b, Direction.Axis axis) {
        return b.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
    }

    public static Plan plan(Building b) {
        RandomSource rng = RandomSource.create(b.seed);
        Frame f = new Frame(b);
        return switch (b.zone) {
            case RESIDENTIAL -> house(f, rng);
            case COMMERCIAL -> shop(f, rng);
            case INDUSTRIAL -> workshop(f, rng);
        };
    }

    private static final Block[][] WOOD = {
            {Blocks.OAK_PLANKS, Blocks.OAK_LOG, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, Blocks.OAK_DOOR},
            {Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_LOG, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, Blocks.SPRUCE_DOOR},
            {Blocks.BIRCH_PLANKS, Blocks.OAK_LOG, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, Blocks.OAK_DOOR},
    };
    private static final Block[] FLOWERS = {Blocks.POPPY, Blocks.DANDELION, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER};

    /** A village cottage: one or two storeys, timber frame, gable roof. */
    private static Plan house(Frame f, RandomSource rng) {
        int w = 5 + rng.nextInt(2), d = 5 + rng.nextInt(2), floors = rng.nextFloat() < 0.35f ? 2 : 1;
        Block[] wood = WOOD[rng.nextInt(WOOD.length)];
        Block roofStairs = rng.nextBoolean() ? Blocks.SPRUCE_STAIRS : Blocks.DARK_OAK_STAIRS;
        Block roofSlab = roofStairs == Blocks.SPRUCE_STAIRS ? Blocks.SPRUCE_SLAB : Blocks.DARK_OAK_SLAB;
        Builder h = new Builder(f, w, d);
        d = h.d; // a set-back lot may be shallower
        int door = w / 2;
        footing(h, Blocks.COBBLESTONE.defaultBlockState(), wood[0].defaultBlockState());
        timberWalls(h, floors, wood[0].defaultBlockState(), wood[1], door, false);
        int roofBase = 4 * floors - 1;
        h.gableRoof(roofBase, roofStairs, roofSlab, wood[0].defaultBlockState());
        h.door(door, wood[4], 0);
        // Inside: a workbench and a lantern. Outside: a torch by the door, a path and flowers.
        h.put(1, 0, d - 2, Blocks.CRAFTING_TABLE.defaultBlockState());
        h.put(w - 2, 0, d - 2, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false));
        h.steps.add(new Step(h.pos(door + 1, 2, -1), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, f.front)));
        h.frontPath(door);
        flowers(h, rng, door);
        int households = 1 + floors + (w * d >= 30 ? 1 : 0);
        return finish(h, households, 0, "house");
    }

    /** A village shop: a workstation behind a wide shop window, under a striped awning. */
    private static Plan shop(Frame f, RandomSource rng) {
        int w = 6, d = 5 + rng.nextInt(2), floors = rng.nextFloat() < 0.4f ? 2 : 1;
        Block[] wood = WOOD[rng.nextInt(WOOD.length)];
        Builder h = new Builder(f, w, d);
        d = h.d; // a set-back lot may be shallower
        int door = 2 + rng.nextInt(2);
        footing(h, Blocks.COBBLESTONE.defaultBlockState(), Blocks.SPRUCE_PLANKS.defaultBlockState());
        timberWalls(h, floors, wood[0].defaultBlockState(), Blocks.STRIPPED_OAK_LOG, door, true);
        h.gableRoof(4 * floors - 1, Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB, wood[0].defaultBlockState());
        h.door(door, wood[4], 0);
        Block[] stations = {Blocks.BARREL, Blocks.SMITHING_TABLE, Blocks.FLETCHING_TABLE, Blocks.CARTOGRAPHY_TABLE, Blocks.LOOM, Blocks.LECTERN};
        Block station = stations[rng.nextInt(stations.length)];
        int counter = door <= 2 ? 4 : 1;
        h.put(counter, 0, 1, facing(station.defaultBlockState(), f.front));
        h.put(w - 1 - counter, 0, d - 2, Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        DyeColor[] colours = {DyeColor.RED, DyeColor.BLUE, DyeColor.GREEN, DyeColor.YELLOW, DyeColor.ORANGE};
        Block colour = Blocks.WOOL.pick(colours[rng.nextInt(colours.length)]);
        for (int hx = 0; hx < w; hx++) h.put(hx, 2, -1, (hx % 2 == 0 ? colour : Blocks.WOOL.pick(DyeColor.WHITE)).defaultBlockState());
        h.put(w - 2, 0, d - 2, Blocks.LANTERN.defaultBlockState());
        h.frontPath(door);
        return finish(h, 0, 3 + floors + (station == Blocks.BARREL ? 0 : 1), "shop");
    }

    /** A village workshop: cobblestone walls, flat roof, smoking chimney, hay and barrels in the yard. */
    private static Plan workshop(Frame f, RandomSource rng) {
        int w = 6, d = 6;
        Builder h = new Builder(f, w, d);
        d = h.d; // a set-back lot may be shallower
        int door = 2;
        footing(h, Blocks.COBBLESTONE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
        for (int y = 0; y <= 2; y++) {
            for (int[] p : h.ring()) {
                int hx = p[0], hz = p[1];
                BlockState s;
                if (h.corner(hx, hz)) s = Blocks.STONE_BRICKS.defaultBlockState();
                else if (hz == 0 && (hx == door || hx == door + 1) && y <= 1) continue; // wide doorway
                else if (y == 1 && hz != 0 && (hx == 0 || hx == w - 1) && hz % 2 == 0) s = Blocks.GLASS_PANE.defaultBlockState();
                else s = Blocks.COBBLESTONE.defaultBlockState();
                h.put(hx, y, hz, s);
            }
        }
        for (int hz = 0; hz < d; hz++) for (int hx = 0; hx < w; hx++) {
            h.put(hx, 3, hz, h.edge(hx, hz) ? Blocks.STONE_BRICK_SLAB.defaultBlockState() : Blocks.COBBLESTONE_SLAB.defaultBlockState());
        }
        h.door(door, Blocks.SPRUCE_DOOR, 0);
        h.door(door + 1, Blocks.SPRUCE_DOOR, 0);
        h.steps.set(h.steps.size() - 1, new Step(h.pos(door + 1, 0, 0), Blocks.SPRUCE_DOOR.defaultBlockState()
                .setValue(DoorBlock.FACING, f.back).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(DoorBlock.HINGE, DoorHingeSide.RIGHT)));
        // Chimney with a lit campfire for smoke.
        for (int y = 4; y <= 5; y++) h.put(w - 1, y, d - 1, Blocks.COBBLESTONE_WALL.defaultBlockState());
        h.put(w - 1, 6, d - 1, Blocks.CAMPFIRE.defaultBlockState());
        Block oven = rng.nextBoolean() ? Blocks.SMOKER : Blocks.BLAST_FURNACE;
        h.put(w - 2, 0, d - 2, facing(oven.defaultBlockState(), f.front));
        h.put(1, 0, d - 2, Blocks.STONECUTTER.defaultBlockState());
        h.put(1, 0, 1, Blocks.COMPOSTER.defaultBlockState());
        // Yard: hay bales by one side, barrels by the other.
        h.putCell(0, 0, 1, Blocks.HAY_BLOCK.defaultBlockState());
        h.putCell(0, 0, 2, Blocks.HAY_BLOCK.defaultBlockState());
        if (rng.nextBoolean()) h.putCell(0, 1, 1, Blocks.HAY_BLOCK.defaultBlockState());
        h.putCell(7, 0, 1, Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        h.frontPath(door);
        h.frontPath(door + 1);
        return finish(h, 0, 5 + rng.nextInt(3), "workshop");
    }

    /** Cobblestone footing ring and a floor, one block below floor level. */
    private static void footing(Builder h, BlockState ring, BlockState floor) {
        for (int hz = 0; hz < h.d; hz++) for (int hx = 0; hx < h.w; hx++) h.put(hx, -1, hz, h.edge(hx, hz) ? ring : floor);
    }

    /** Log posts at the corners, plank walls with windows, and a log beam between storeys. */
    private static void timberWalls(Builder h, int floors, BlockState wall, Block log, int door, boolean shopFront) {
        for (int floor = 0; floor < floors; floor++) {
            int y0 = floor * 4;
            for (int y = y0; y <= y0 + 2; y++) {
                for (int[] p : h.ring()) {
                    int hx = p[0], hz = p[1];
                    BlockState s;
                    if (h.corner(hx, hz)) s = log(log, Direction.Axis.Y);
                    else if (floor == 0 && hz == 0 && hx == door && y <= 1) continue; // doorway
                    else if (shopFront && floor == 0 && hz == 0 && y <= 1) s = Blocks.GLASS_PANE.defaultBlockState();
                    else if (y == y0 + 1 && window(h, hx, hz)) s = Blocks.GLASS_PANE.defaultBlockState();
                    else s = wall;
                    h.put(hx, y, hz, s);
                }
            }
            if (floor + 1 < floors) {
                int y = y0 + 3;
                for (int hz = 0; hz < h.d; hz++) for (int hx = 0; hx < h.w; hx++) {
                    boolean alongX = hz == 0 || hz == h.d - 1;
                    h.put(hx, y, hz, h.edge(hx, hz) ? log(log, alongX ? h.f.right.getAxis() : h.f.back.getAxis()) : wall);
                }
            }
        }
    }

    private static boolean window(Builder h, int hx, int hz) {
        if (h.corner(hx, hz)) return false;
        if (hz == 0 || hz == h.d - 1) return hx % 2 == 1 && hx != h.w - 1;
        return hz % 2 == 1 || hz == h.d / 2;
    }

    private static void flowers(Builder h, RandomSource rng, int door) {
        for (int lz = 0; lz < h.oz; lz++) {
            for (int lx = 0; lx < ZoneRules.CELL; lx++) {
                if (lx == h.ox + door || rng.nextFloat() > 0.18f) continue;
                h.putCell(lx, 0, lz, FLOWERS[rng.nextInt(FLOWERS.length)].defaultBlockState());
            }
        }
    }

    private static Plan finish(Builder h, int households, int jobs, String kind) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Step s : h.steps) {
            minX = Math.min(minX, s.pos().getX()); minY = Math.min(minY, s.pos().getY()); minZ = Math.min(minZ, s.pos().getZ());
            maxX = Math.max(maxX, s.pos().getX()); maxY = Math.max(maxY, s.pos().getY()); maxZ = Math.max(maxZ, s.pos().getZ());
        }
        // Scaffolding stands at the sides of the house while it goes up.
        List<BlockPos> scaffold = List.of(h.pos(-1, 0, 1), h.pos(h.w, 0, 1), h.pos(-1, 0, h.d - 2), h.pos(h.w, 0, h.d - 2));
        return new Plan(List.copyOf(h.steps), new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), households, jobs, kind, scaffold);
    }

    private BuildingGenerator() {}
}
