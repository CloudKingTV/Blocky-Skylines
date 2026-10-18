package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.service.ServiceType;
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
 * Procedural village buildings in the vocabulary of vanilla villages: stone footings, corner posts, plank walls,
 * glass-pane windows, stair-and-slab gable roofs (flat roofs in the desert), lanterns and flowers. Each zone has a
 * building type, each type three levels, and each level is built from the palette of the lot's biome.
 *
 * <ul>
 *   <li>Cottage (low-density homes): one storey, two from level 2, wider with a chimney at level 3.</li>
 *   <li>Village tower (high-density homes): a stone podium and 3-5 timber storeys with front balconies and a
 *       lantern crown. "Stacked villager houses", per the design.</li>
 *   <li>Shop: a workstation behind a glass shop front, under a striped awning; a second storey from level 2.</li>
 *   <li>Market hall (high-density shops): glass on three sides at street level, several stalls, 2-4 storeys.</li>
 *   <li>Workshop (industry): stone walls, flat roof, smoking chimney; a loft and more chimneys as it levels.</li>
 *   <li>Guild hall (offices): all stone, tall windows, lecterns and map tables, a railed roof terrace.</li>
 * </ul>
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

    /** Builder over the footprint: hx across, hz from the front wall back, y up from the floor. */
    private static final class Builder {
        final Frame f;
        final Palette p;
        final int ox, oz, w, d;
        final List<Step> steps = new ArrayList<>();

        Builder(Frame f, Palette p, int w, int d) {
            this.f = f;
            this.p = p;
            this.w = w;
            this.d = Math.min(d, f.maxDepth);
            this.ox = (ZoneRules.CELL - w) / 2;
            this.oz = ZoneRules.CELL - this.d - 1;
        }

        BlockPos pos(int hx, int y, int hz) { return f.at(ox + hx, y, oz + hz); }
        void put(int hx, int y, int hz, BlockState s) { steps.add(new Step(pos(hx, y, hz), s)); }
        void put(int hx, int y, int hz, Block b) { put(hx, y, hz, b.defaultBlockState()); }
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

        BlockState slab(Block block, SlabType type) {
            return block.defaultBlockState().setValue(SlabBlock.TYPE, type);
        }

        /** Gable roof, ridge across the width, one block of eave all round. Returns the ridge height. */
        int gableRoof(int roofBase, BlockState gable) {
            int top = roofBase;
            for (int k = 0; ; k++) {
                int fz = -1 + k, bz = d - k;
                if (fz > bz) break;
                int y = roofBase + k;
                top = y;
                for (int hx = -1; hx <= w; hx++) {
                    if (fz == bz) put(hx, y, fz, slab(p.roofSlab(), SlabType.BOTTOM));
                    else {
                        put(hx, y, fz, stairs(p.roofStairs(), f.back));
                        put(hx, y, bz, stairs(p.roofStairs(), f.front));
                    }
                }
                for (int hz = k; hz <= d - 1 - k; hz++) {
                    put(0, y, hz, gable);
                    put(w - 1, y, hz, gable);
                }
            }
            return top;
        }

        /** Flat roof with a parapet ring (desert style). */
        void flatRoof(int roofBase) {
            for (int hz = 0; hz < d; hz++) for (int hx = 0; hx < w; hx++) put(hx, roofBase, hz, p.stone());
            for (int[] r : ring()) put(r[0], roofBase + 1, r[1], slab(p.stoneSlab(), SlabType.BOTTOM));
        }

        /** The roof the palette uses; returns the highest roof y. */
        int roof(int roofBase, BlockState gable) {
            if (p.flatRoof()) { flatRoof(roofBase); return roofBase + 1; }
            return gableRoof(roofBase, gable);
        }

        void door(int hx, Block door, DoorHingeSide hinge) {
            put(hx, 0, 0, door.defaultBlockState().setValue(DoorBlock.FACING, f.back).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.HINGE, hinge));
        }

        /** A dirt path from the door to the road edge of the cell. */
        void frontPath(int hx) {
            for (int lz = 0; lz < oz; lz++) putCell(ox + hx, -1, lz, Blocks.DIRT_PATH.defaultBlockState());
        }

        /** Light by the door: a lantern hanging from the eave where there is one, else a wall torch. */
        void doorLight(int hx, boolean eave) {
            if (eave) put(hx, 2, -1, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            else steps.add(new Step(pos(hx, 2, -1), Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, f.front)));
        }
    }

    private static BlockState facing(BlockState s, Direction d) {
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return s.setValue(BlockStateProperties.HORIZONTAL_FACING, d);
        if (s.hasProperty(BlockStateProperties.FACING)) return s.setValue(BlockStateProperties.FACING, d);
        return s;
    }

    private static BlockState post(Block b, Direction.Axis axis) {
        BlockState s = b.defaultBlockState();
        return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, axis) : s;
    }

    public static Plan plan(Building b) {
        return plan(b, b.level);
    }

    /** The plan at a given level (upgrades compare the old level's plan with the new one). */
    public static Plan plan(Building b, int level) {
        RandomSource rng = RandomSource.create(b.seed);
        Frame f = new Frame(b);
        Palette p = Palette.of(b.style);
        int lv = Math.max(1, Math.min(Building.MAX_LEVEL, level));
        return switch (b.zone) {
            case RESIDENTIAL -> cottage(f, p, rng, lv);
            case RESIDENTIAL_HIGH -> tower(f, p, rng, lv);
            case COMMERCIAL -> shop(f, p, rng, lv);
            case COMMERCIAL_HIGH -> marketHall(f, p, rng, lv);
            case INDUSTRIAL -> workshop(f, p, rng, lv);
            case OFFICE -> guildHall(f, p, rng, lv);
            case CIVIC -> civic(f, p, rng, b.serviceType());
        };
    }

    // ---------------------------------------------------------------- services and utilities

    private static Plan civic(Frame f, Palette p, RandomSource rng, ServiceType s) {
        return switch (s) {
            case WINDMILL -> windmill(f, p, rng);
            case WATERMILL -> watermill(f, p, rng);
            case WELL -> well(f, p);
            case WATER_TOWER -> waterTower(f, p);
            case CLINIC -> clinic(f, p, rng);
            case FIRE_STATION -> fireStation(f, p);
            case GUARD_HOUSE -> guardHouse(f, p);
            case SCHOOL -> school(f, p, rng);
            case COMPOST_YARD -> compostYard(f, p);
            case PARK -> park(f, p, rng);
        };
    }

    private static Plan windmill(Frame f, Palette p, RandomSource rng) {
        Builder h = new Builder(f, p, 5, 5);
        Block wall = p.walls().get(rng.nextInt(p.walls().size()));
        footing(h, p.foundation(), p.floor());
        for (int y = 0; y <= 9; y++) {
            for (int[] r : h.ring()) {
                int hx = r[0], hz = r[1];
                if (hz == 0 && hx == 2 && y <= 1) continue;
                BlockState st = y <= 2 ? p.foundation().defaultBlockState()
                        : h.corner(hx, hz) ? post(p.post(), Direction.Axis.Y)
                        : (y == 5 || y == 8) && !h.corner(hx, hz) && hx % 2 == 0 && hz % 2 == 0 ? Blocks.GLASS_PANE.defaultBlockState()
                        : wall.defaultBlockState();
                h.put(hx, y, hz, st);
            }
        }
        h.roof(10, wall.defaultBlockState());
        h.door(2, p.door(), DoorHingeSide.LEFT);
        // The sails: a hub on the front face and four white arms.
        h.put(2, 7, -1, post(p.post(), h.f.back.getAxis()));
        BlockState sail = Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState();
        for (int k = 1; k <= 3; k++) {
            h.put(2, 7 + k, -1, sail);
            h.put(2, 7 - k, -1, sail);
            h.put(2 - k, 7, -1, sail);
            h.put(2 + k, 7, -1, sail);
        }
        h.put(1, 0, 3, Blocks.GRINDSTONE);
        h.put(3, 0, 3, Blocks.HAY_BLOCK);
        h.frontPath(2);
        return finish(h, 0, ServiceType.WINDMILL.jobs, "windmill");
    }

    private static Plan watermill(Frame f, Palette p, RandomSource rng) {
        Builder h = new Builder(f, p, 5, 5);
        Block wall = p.walls().get(rng.nextInt(p.walls().size()));
        footing(h, p.foundation(), p.floor());
        timberWalls(h, 1, wall.defaultBlockState(), p.post(), p.trim(), 2, false);
        h.roof(3, wall.defaultBlockState());
        h.door(2, p.door(), DoorHingeSide.LEFT);
        // The wheel turns on the side wall: a ring of planks round a log axle.
        int cz = h.d / 2, cy = 2;
        h.put(h.w, cy, cz, post(p.post(), h.f.right.getAxis()));
        for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++) {
            int r2 = dy * dy + dz * dz;
            if (r2 >= 3 && r2 <= 5 || r2 == 4) h.put(h.w + 1, cy + dy, cz + dz, p.walls().getFirst().defaultBlockState());
            else if ((dy == 0) != (dz == 0) && r2 <= 2) h.put(h.w + 1, cy + dy, cz + dz, post(p.post(), dy == 0 ? h.f.back.getAxis() : Direction.Axis.Y));
        }
        h.put(1, 0, 3, Blocks.GRINDSTONE);
        h.put(3, 0, 3, Blocks.BARREL);
        h.frontPath(2);
        return finish(h, 0, ServiceType.WATERMILL.jobs, "watermill");
    }

    private static Plan well(Frame f, Palette p) {
        Builder h = new Builder(f, p, 4, 4);
        for (int hz = 0; hz < 4; hz++) for (int hx = 0; hx < 4; hx++) {
            boolean inner = hx >= 1 && hx <= 2 && hz >= 1 && hz <= 2;
            h.put(hx, -2, hz, p.foundation());
            h.put(hx, -1, hz, inner ? Blocks.WATER.defaultBlockState() : p.foundation().defaultBlockState());
            if (!inner) h.put(hx, 0, hz, p.foundation());
        }
        for (int[] c : new int[][] {{0, 0}, {3, 0}, {0, 3}, {3, 3}}) {
            h.put(c[0], 1, c[1], p.fence());
            h.put(c[0], 2, c[1], p.fence());
        }
        for (int hz = 0; hz < 4; hz++) for (int hx = 0; hx < 4; hx++) h.put(hx, 3, hz, h.slab(p.roofSlab(), SlabType.BOTTOM));
        h.put(1, 2, 1, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        h.frontPath(1);
        return finish(h, 0, ServiceType.WELL.jobs, "well");
    }

    private static Plan waterTower(Frame f, Palette p) {
        Builder h = new Builder(f, p, 4, 4);
        for (int[] c : new int[][] {{0, 0}, {3, 0}, {0, 3}, {3, 3}}) {
            for (int y = 0; y <= 5; y++) h.put(c[0], y, c[1], post(p.post(), Direction.Axis.Y));
        }
        for (int y = 6; y <= 8; y++) {
            for (int[] r : h.ring()) h.put(r[0], y, r[1], y == 6 ? p.walls().getFirst().defaultBlockState() : Blocks.BARREL.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, h.f.front));
        }
        for (int hz = 1; hz <= 2; hz++) for (int hx = 1; hx <= 2; hx++) {
            h.put(hx, 6, hz, p.walls().getFirst());
            h.put(hx, 7, hz, Blocks.WATER);
        }
        for (int hz = -1; hz <= 4; hz++) for (int hx = -1; hx <= 4; hx++) h.put(hx, 9, hz, h.slab(p.roofSlab(), SlabType.BOTTOM));
        h.put(1, 0, 1, Blocks.CAULDRON);
        return finish(h, 0, ServiceType.WATER_TOWER.jobs, "water_tower");
    }

    private static Plan clinic(Frame f, Palette p, RandomSource rng) {
        Builder h = new Builder(f, p, 6, 5);
        Block wall = p.walls().get(rng.nextInt(p.walls().size()));
        footing(h, p.foundation(), p.floor());
        timberWalls(h, 1, wall.defaultBlockState(), p.post(), p.trim(), 2, false);
        h.roof(3, wall.defaultBlockState());
        h.door(2, p.door(), DoorHingeSide.LEFT);
        // A red cross on the front, a brewing stand and beds of wool inside.
        Block red = Blocks.WOOL.pick(DyeColor.RED), white = Blocks.WOOL.pick(DyeColor.WHITE);
        h.put(3, 2, -1, red.defaultBlockState());
        h.put(4, 2, -1, white.defaultBlockState());
        h.put(1, 0, 1, Blocks.BREWING_STAND);
        h.put(4, 0, 3, white.defaultBlockState());
        h.put(3, 0, 3, white.defaultBlockState());
        h.put(1, 0, 3, Blocks.CAULDRON);
        h.doorLight(1, false);
        h.frontPath(2);
        return finish(h, 0, ServiceType.CLINIC.jobs, "clinic");
    }

    private static Plan fireStation(Frame f, Palette p) {
        Builder h = new Builder(f, p, 6, 6);
        footing(h, p.foundation(), p.foundation());
        for (int y = 0; y <= 3; y++) {
            for (int[] r : h.ring()) {
                int hx = r[0], hz = r[1];
                if (hz == 0 && (hx == 2 || hx == 3) && y <= 1) continue;
                h.put(hx, y, hz, y == 3 ? p.trim().defaultBlockState() : y == 1 && !h.corner(hx, hz) && hz > 0 && hz % 2 == 1
                        ? Blocks.GLASS_PANE.defaultBlockState() : p.stone().defaultBlockState());
            }
        }
        for (int hz = 0; hz < h.d; hz++) for (int hx = 0; hx < h.w; hx++) h.put(hx, 4, hz, h.slab(p.stoneSlab(), SlabType.BOTTOM));
        // Watch tower in the back corner with the alarm bell on top.
        for (int y = 4; y <= 7; y++) h.put(h.w - 1, y, h.d - 1, post(p.post(), Direction.Axis.Y));
        h.put(h.w - 1, 8, h.d - 1, p.stone());
        h.put(h.w - 2, 8, h.d - 1, Blocks.BELL);
        h.door(2, p.door(), DoorHingeSide.LEFT);
        h.door(3, p.door(), DoorHingeSide.RIGHT);
        h.put(1, 0, 1, Blocks.WATER_CAULDRON);
        h.put(h.w - 2, 0, 1, Blocks.WATER_CAULDRON);
        h.put(1, 0, h.d - 2, Blocks.BARREL);
        h.frontPath(2);
        h.frontPath(3);
        return finish(h, 0, ServiceType.FIRE_STATION.jobs, "fire_station");
    }

    private static Plan guardHouse(Frame f, Palette p) {
        Builder h = new Builder(f, p, 5, 5);
        footing(h, p.foundation(), p.foundation());
        for (int y = 0; y <= 3; y++) {
            for (int[] r : h.ring()) {
                int hx = r[0], hz = r[1];
                if (hz == 0 && hx == 2 && y <= 1) continue;
                h.put(hx, y, hz, y == 1 && !h.corner(hx, hz) && (hx + hz) % 2 == 1 ? Blocks.IRON_BARS.defaultBlockState()
                        : h.corner(hx, hz) ? p.stone().defaultBlockState() : p.foundation().defaultBlockState());
            }
        }
        for (int hz = 0; hz < h.d; hz++) for (int hx = 0; hx < h.w; hx++) h.put(hx, 4, hz, p.stone());
        for (int[] r : h.ring()) h.put(r[0], 5, r[1], h.corner(r[0], r[1]) ? Blocks.LANTERN.defaultBlockState() : p.fence().defaultBlockState());
        h.door(2, p.door(), DoorHingeSide.LEFT);
        h.put(1, 0, 3, Blocks.SMITHING_TABLE);
        h.put(3, 0, 3, Blocks.GRINDSTONE);
        h.doorLight(1, false);
        h.frontPath(2);
        return finish(h, 0, ServiceType.GUARD_HOUSE.jobs, "guard_house");
    }

    private static Plan school(Frame f, Palette p, RandomSource rng) {
        Builder h = new Builder(f, p, 6, 6);
        Block wall = p.walls().get(rng.nextInt(p.walls().size()));
        footing(h, p.foundation(), p.floor());
        timberWalls(h, 2, wall.defaultBlockState(), p.post(), p.trim(), 3, false);
        int top = h.roof(7, wall.defaultBlockState());
        h.put(h.w / 2, top + 1, h.d / 2, Blocks.BELL);
        h.door(3, p.door(), DoorHingeSide.LEFT);
        for (int hx = 1; hx <= 4; hx += 3) h.put(hx, 0, 2, facing(Blocks.LECTERN.defaultBlockState(), f.front));
        for (int hx = 1; hx <= 4; hx++) h.put(hx, 0, h.d - 2, Blocks.BOOKSHELF);
        h.doorLight(2, !p.flatRoof());
        h.frontPath(3);
        return finish(h, 0, ServiceType.SCHOOL.jobs, "school");
    }

    private static Plan compostYard(Frame f, Palette p) {
        Builder h = new Builder(f, p, 6, 6);
        for (int[] r : h.ring()) {
            if (r[1] == 0 && (r[0] == 2 || r[0] == 3)) continue; // gate
            h.put(r[0], 0, r[1], p.fence());
        }
        for (int hx = 1; hx <= 4; hx++) h.put(hx, 0, h.d - 2, Blocks.COMPOSTER);
        h.put(1, 0, 1, Blocks.HAY_BLOCK);
        h.put(1, 1, 1, Blocks.HAY_BLOCK);
        h.put(4, 0, 1, Blocks.HAY_BLOCK);
        // A lean-to over the composters.
        for (int hx = 1; hx <= 4; hx++) h.put(hx, 2, h.d - 2, h.slab(p.roofSlab(), SlabType.BOTTOM));
        h.put(1, 1, h.d - 2, p.fence());
        h.put(4, 1, h.d - 2, p.fence());
        h.frontPath(2);
        h.frontPath(3);
        return finish(h, 0, ServiceType.COMPOST_YARD.jobs, "compost_yard");
    }

    private static Plan park(Frame f, Palette p, RandomSource rng) {
        Builder h = new Builder(f, p, 6, 6);
        // Paths in a cross, two small trees, benches, lamps and flowers.
        for (int i = 0; i < 6; i++) {
            h.put(2, -1, i, Blocks.DIRT_PATH);
            h.put(i, -1, 2, Blocks.DIRT_PATH);
        }
        for (int[] t : new int[][] {{0, 4}, {4, 0}}) {
            for (int y = 0; y <= 2; y++) h.put(t[0], y, t[1], post(p.post(), Direction.Axis.Y));
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) h.put(t[0] + dx, 2, t[1] + dz, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
                h.put(t[0] + dx, 3, t[1] + dz, Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
            }
        }
        h.put(1, 0, 3, h.stairs(p.roofStairs(), f.right));
        h.put(3, 0, 1, h.stairs(p.roofStairs(), f.back));
        for (int[] l : new int[][] {{5, 5}, {1, 1}}) {
            h.put(l[0], 0, l[1], p.fence());
            h.put(l[0], 1, l[1], Blocks.LANTERN);
        }
        for (int i = 0; i < 6; i++) {
            int hx = rng.nextInt(6), hz = rng.nextInt(6);
            if (hx == 2 || hz == 2) continue;
            h.put(hx, 0, hz, Palette.PLAINS.plants().get(rng.nextInt(Palette.PLAINS.plants().size())));
        }
        h.frontPath(2);
        return finish(h, 0, ServiceType.PARK.jobs, "park");
    }

    // ---------------------------------------------------------------- homes

    private static Plan cottage(Frame f, Palette p, RandomSource rng, int level) {
        int w = level >= 3 ? 6 : 5 + rng.nextInt(2), d = level >= 3 ? 6 : 5 + rng.nextInt(2);
        int floors = level >= 2 ? 2 : rng.nextFloat() < 0.3f ? 2 : 1;
        Block wall = p.walls().get(rng.nextInt(p.walls().size()));
        Builder h = new Builder(f, p, w, d);
        int door = w / 2;
        footing(h, p.foundation(), p.floor());
        timberWalls(h, floors, wall.defaultBlockState(), p.post(), p.trim(), door, false);
        h.roof(4 * floors - 1, wall.defaultBlockState());
        h.door(door, p.door(), DoorHingeSide.LEFT);
        h.put(1, 0, h.d - 2, Blocks.CRAFTING_TABLE);
        h.put(w - 2, 0, h.d - 2, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, false));
        h.doorLight(door + 1, level >= 2 && !p.flatRoof());
        if (level >= 3) chimney(h, 4 * floors + (p.flatRoof() ? 1 : 2));
        h.frontPath(door);
        plants(h, rng, door, p);
        int households = 1 + floors + (level - 1) + (w * h.d >= 30 ? 1 : 0);
        return finish(h, households, 0, "cottage");
    }

    private static Plan tower(Frame f, Palette p, RandomSource rng, int level) {
        int floors = 2 + level;
        Block wall = p.walls().get(rng.nextInt(p.walls().size()));
        Builder h = new Builder(f, p, 6, 6);
        int door = 2 + rng.nextInt(2);
        footing(h, p.foundation(), p.floor());
        // Stone podium: the ground floor of a village tower.
        for (int y = 0; y <= 2; y++) {
            for (int[] r : h.ring()) {
                int hx = r[0], hz = r[1];
                if (hz == 0 && hx == door && y <= 1) continue;
                boolean window = y == 1 && !h.corner(hx, hz) && window(h, hx, hz);
                h.put(hx, y, hz, window ? Blocks.GLASS_PANE.defaultBlockState() : p.stone().defaultBlockState());
            }
        }
        beam(h, 3, p.post(), wall.defaultBlockState());
        for (int floor = 1; floor < floors; floor++) {
            int y0 = floor * 4;
            for (int y = y0; y <= y0 + 2; y++) {
                for (int[] r : h.ring()) {
                    int hx = r[0], hz = r[1];
                    BlockState s = h.corner(hx, hz) ? post(p.post(), Direction.Axis.Y)
                            : y == y0 + 1 && window(h, hx, hz) ? Blocks.GLASS_PANE.defaultBlockState() : wall.defaultBlockState();
                    h.put(hx, y, hz, s);
                }
            }
            if (floor + 1 < floors) beam(h, y0 + 3, p.post(), wall.defaultBlockState());
            // A small front balcony on every upper storey.
            for (int hx = 1; hx <= h.w - 2; hx++) {
                h.put(hx, y0 - 1, -1, h.slab(p.roofSlab(), SlabType.TOP));
                h.put(hx, y0, -1, p.fence());
            }
        }
        int top = h.roof(4 * floors - 1, wall.defaultBlockState());
        // Lantern crown on the ridge.
        h.put(1, top + 1, h.d / 2, Blocks.LANTERN);
        h.put(h.w - 2, top + 1, h.d / 2, Blocks.LANTERN);
        h.door(door, p.door(), DoorHingeSide.LEFT);
        h.doorLight(door + 1, false);
        h.put(1, 0, h.d - 2, Blocks.CRAFTING_TABLE);
        h.frontPath(door);
        plants(h, rng, door, p);
        return finish(h, floors * 2 + level, 0, "tower");
    }

    // ---------------------------------------------------------------- shops

    private static final Block[] STATIONS = {Blocks.BARREL, Blocks.SMITHING_TABLE, Blocks.FLETCHING_TABLE, Blocks.CARTOGRAPHY_TABLE,
            Blocks.LOOM, Blocks.LECTERN, Blocks.BREWING_STAND, Blocks.GRINDSTONE};
    private static final DyeColor[] AWNINGS = {DyeColor.RED, DyeColor.BLUE, DyeColor.GREEN, DyeColor.YELLOW, DyeColor.ORANGE};

    private static Plan shop(Frame f, Palette p, RandomSource rng, int level) {
        int floors = level >= 2 ? 2 : 1;
        Block wall = p.walls().get(rng.nextInt(p.walls().size()));
        Builder h = new Builder(f, p, 6, 5 + rng.nextInt(2));
        int door = 2 + rng.nextInt(2);
        footing(h, p.foundation(), p.floor());
        timberWalls(h, floors, wall.defaultBlockState(), p.post(), p.trim(), door, true);
        h.roof(4 * floors - 1, wall.defaultBlockState());
        h.door(door, p.door(), DoorHingeSide.LEFT);
        Block station = STATIONS[rng.nextInt(STATIONS.length)];
        int counter = door <= 2 ? 4 : 1;
        h.put(counter, 0, 1, facing(station.defaultBlockState(), f.front));
        h.put(h.w - 1 - counter, 0, h.d - 2, Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        if (level >= 3) h.put(counter, 0, h.d - 2, facing(STATIONS[(rng.nextInt(STATIONS.length))].defaultBlockState(), f.front));
        awning(h, rng, 2);
        h.put(h.w - 2, 0, h.d - 2, Blocks.LANTERN);
        h.frontPath(door);
        return finish(h, 0, 3 + level * 2, "shop");
    }

    private static Plan marketHall(Frame f, Palette p, RandomSource rng, int level) {
        int floors = 1 + level;
        Block wall = p.walls().get(rng.nextInt(p.walls().size()));
        Builder h = new Builder(f, p, 6, 6);
        int door = 2 + rng.nextInt(2);
        footing(h, p.foundation(), p.floor());
        // Street level: glass on the front and both sides.
        for (int y = 0; y <= 2; y++) {
            for (int[] r : h.ring()) {
                int hx = r[0], hz = r[1];
                if (hz == 0 && hx == door && y <= 1) continue;
                boolean glass = y <= 1 && !h.corner(hx, hz) && hz != h.d - 1;
                BlockState s = h.corner(hx, hz) ? post(p.post(), Direction.Axis.Y) : glass ? Blocks.GLASS_PANE.defaultBlockState()
                        : y == 2 ? p.trim().defaultBlockState() : wall.defaultBlockState();
                h.put(hx, y, hz, s);
            }
        }
        beam(h, 3, p.post(), wall.defaultBlockState());
        for (int floor = 1; floor < floors; floor++) {
            int y0 = floor * 4;
            for (int y = y0; y <= y0 + 2; y++) {
                for (int[] r : h.ring()) {
                    int hx = r[0], hz = r[1];
                    BlockState s = h.corner(hx, hz) ? post(p.post(), Direction.Axis.Y)
                            : y == y0 + 1 && window(h, hx, hz) ? Blocks.GLASS_PANE.defaultBlockState() : wall.defaultBlockState();
                    h.put(hx, y, hz, s);
                }
            }
            if (floor + 1 < floors) beam(h, y0 + 3, p.post(), wall.defaultBlockState());
        }
        h.roof(4 * floors - 1, wall.defaultBlockState());
        h.door(door, p.door(), DoorHingeSide.LEFT);
        // Market stalls: a different workstation along each side.
        for (int i = 0; i < 2 + level; i++) {
            int hx = i % 2 == 0 ? 1 : h.w - 2, hz = 1 + i / 2;
            if (hz >= h.d - 1) break;
            h.put(hx, 0, hz, facing(STATIONS[(rng.nextInt(STATIONS.length))].defaultBlockState(), i % 2 == 0 ? f.right : f.left));
        }
        awning(h, rng, 2);
        h.put(door, 0, h.d - 2, Blocks.LANTERN);
        h.frontPath(door);
        return finish(h, 0, 6 + level * 3, "market");
    }

    private static void awning(Builder h, RandomSource rng, int y) {
        Block colour = Blocks.WOOL.pick(AWNINGS[rng.nextInt(AWNINGS.length)]);
        for (int hx = 0; hx < h.w; hx++) h.put(hx, y, -1, (hx % 2 == 0 ? colour : Blocks.WOOL.pick(DyeColor.WHITE)).defaultBlockState());
    }

    // ---------------------------------------------------------------- work

    private static Plan workshop(Frame f, Palette p, RandomSource rng, int level) {
        Builder h = new Builder(f, p, 6, 6);
        int door = 2;
        footing(h, p.foundation(), p.foundation());
        int walls = level >= 2 ? 6 : 2;
        for (int y = 0; y <= walls; y++) {
            if (y == 3) { beam(h, 3, p.post(), p.foundation().defaultBlockState()); continue; }
            for (int[] r : h.ring()) {
                int hx = r[0], hz = r[1];
                BlockState s;
                if (h.corner(hx, hz)) s = p.stone().defaultBlockState();
                else if (hz == 0 && (hx == door || hx == door + 1) && y <= 1) continue; // wide doorway
                else if ((y == 1 || y == 5) && hz != 0 && (hx == 0 || hx == h.w - 1) && hz % 2 == 0) s = Blocks.GLASS_PANE.defaultBlockState();
                else if (y >= 4) s = p.walls().getFirst().defaultBlockState(); // a timber loft from level 2
                else s = p.foundation().defaultBlockState();
                h.put(hx, y, hz, s);
            }
        }
        int roof = walls + 1;
        for (int hz = 0; hz < h.d; hz++) for (int hx = 0; hx < h.w; hx++) {
            h.put(hx, roof, hz, h.slab(h.edge(hx, hz) ? p.stoneSlab() : p.stoneSlab(), SlabType.BOTTOM));
        }
        h.door(door, p.door(), DoorHingeSide.LEFT);
        h.door(door + 1, p.door(), DoorHingeSide.RIGHT);
        // Chimneys with lit campfires for smoke: one per level.
        int[][] chimneys = {{h.w - 1, h.d - 1}, {0, h.d - 1}, {h.w - 1, 0}};
        for (int c = 0; c < level; c++) {
            for (int y = roof + 1; y <= roof + 2; y++) h.put(chimneys[c][0], y, chimneys[c][1], Blocks.COBBLESTONE_WALL);
            h.put(chimneys[c][0], roof + 3, chimneys[c][1], Blocks.CAMPFIRE);
        }
        Block oven = rng.nextBoolean() ? Blocks.SMOKER : Blocks.BLAST_FURNACE;
        h.put(h.w - 2, 0, h.d - 2, facing(oven.defaultBlockState(), f.front));
        h.put(1, 0, h.d - 2, Blocks.STONECUTTER);
        h.put(1, 0, 1, Blocks.COMPOSTER);
        if (level >= 3) h.put(h.w - 2, 0, 1, facing(Blocks.BLAST_FURNACE.defaultBlockState(), f.right));
        // Yard: hay bales by one side, barrels by the other.
        h.putCell(0, 0, 1, Blocks.HAY_BLOCK.defaultBlockState());
        h.putCell(0, 0, 2, Blocks.HAY_BLOCK.defaultBlockState());
        if (level >= 2 || rng.nextBoolean()) h.putCell(0, 1, 1, Blocks.HAY_BLOCK.defaultBlockState());
        h.putCell(7, 0, 1, Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        if (level >= 2) h.putCell(7, 0, 2, Blocks.BARREL.defaultBlockState().setValue(BlockStateProperties.FACING, Direction.UP));
        h.frontPath(door);
        h.frontPath(door + 1);
        return finish(h, 0, 5 + (level - 1) * 3 + rng.nextInt(2), "workshop");
    }

    private static Plan guildHall(Frame f, Palette p, RandomSource rng, int level) {
        int floors = 2 + level;
        Builder h = new Builder(f, p, 6, 6);
        int door = 2;
        footing(h, p.foundation(), p.floor());
        for (int floor = 0; floor < floors; floor++) {
            int y0 = floor * 4;
            for (int y = y0; y <= y0 + 3; y++) {
                for (int[] r : h.ring()) {
                    int hx = r[0], hz = r[1];
                    if (floor == 0 && hz == 0 && (hx == door || hx == door + 1) && y <= 1) continue;
                    boolean tall = (y == y0 + 1 || y == y0 + 2) && !h.corner(hx, hz) && (hz == 0 || hz == h.d - 1 ? hx % 2 == 1 : hz % 2 == 1);
                    BlockState s = y == y0 + 3 ? p.trim().defaultBlockState() : tall ? Blocks.GLASS_PANE.defaultBlockState() : p.stone().defaultBlockState();
                    h.put(hx, y, hz, s);
                }
            }
        }
        int roof = floors * 4;
        for (int hz = 0; hz < h.d; hz++) for (int hx = 0; hx < h.w; hx++) h.put(hx, roof, hz, h.slab(p.stoneSlab(), SlabType.BOTTOM));
        for (int[] r : h.ring()) h.put(r[0], roof + 1, r[1], h.corner(r[0], r[1]) ? Blocks.LANTERN.defaultBlockState() : p.fence().defaultBlockState());
        h.door(door, p.door(), DoorHingeSide.LEFT);
        h.door(door + 1, p.door(), DoorHingeSide.RIGHT);
        h.put(1, 0, 1, facing(Blocks.LECTERN.defaultBlockState(), f.front));
        h.put(h.w - 2, 0, 1, Blocks.CARTOGRAPHY_TABLE);
        h.put(1, 0, h.d - 2, Blocks.BOOKSHELF);
        h.put(h.w - 2, 0, h.d - 2, Blocks.BOOKSHELF);
        h.doorLight(door - 1, false);
        h.frontPath(door);
        h.frontPath(door + 1);
        plants(h, rng, door, p);
        return finish(h, 0, 8 + level * 4, "guild");
    }

    // ---------------------------------------------------------------- parts

    /** Footing ring and a floor, one block below floor level. */
    private static void footing(Builder h, Block ring, Block floor) {
        for (int hz = 0; hz < h.d; hz++) for (int hx = 0; hx < h.w; hx++) h.put(hx, -1, hz, h.edge(hx, hz) ? ring : floor);
    }

    /** A band between storeys: horizontal posts round the edge, floorboards inside. */
    private static void beam(Builder h, int y, Block post, BlockState inside) {
        for (int hz = 0; hz < h.d; hz++) for (int hx = 0; hx < h.w; hx++) {
            boolean alongX = hz == 0 || hz == h.d - 1;
            h.put(hx, y, hz, h.edge(hx, hz) ? post(post, alongX ? h.f.right.getAxis() : h.f.back.getAxis()) : inside);
        }
    }

    /** Corner posts, walls with windows, and a beam between storeys. */
    private static void timberWalls(Builder h, int floors, BlockState wall, Block post, Block trim, int door, boolean shopFront) {
        for (int floor = 0; floor < floors; floor++) {
            int y0 = floor * 4;
            for (int y = y0; y <= y0 + 2; y++) {
                for (int[] r : h.ring()) {
                    int hx = r[0], hz = r[1];
                    BlockState s;
                    if (h.corner(hx, hz)) s = post(post, Direction.Axis.Y);
                    else if (floor == 0 && hz == 0 && hx == door && y <= 1) continue; // doorway
                    else if (shopFront && floor == 0 && hz == 0 && y <= 1) s = Blocks.GLASS_PANE.defaultBlockState();
                    else if (y == y0 + 1 && window(h, hx, hz)) s = Blocks.GLASS_PANE.defaultBlockState();
                    else s = wall;
                    h.put(hx, y, hz, s);
                }
            }
            if (floor + 1 < floors) beam(h, y0 + 3, trim.defaultBlockState().hasProperty(RotatedPillarBlock.AXIS) ? trim : post, wall);
        }
    }

    private static boolean window(Builder h, int hx, int hz) {
        if (h.corner(hx, hz)) return false;
        if (hz == 0 || hz == h.d - 1) return hx % 2 == 1 && hx != h.w - 1;
        return hz % 2 == 1 || hz == h.d / 2;
    }

    /** A stone chimney up the outside of a side wall, smoking. */
    private static void chimney(Builder h, int top) {
        for (int y = 0; y <= top; y++) h.put(h.w, y, h.d - 2, Blocks.COBBLESTONE);
        h.put(h.w, top + 1, h.d - 2, Blocks.CAMPFIRE);
    }

    private static void plants(Builder h, RandomSource rng, int door, Palette p) {
        if (p.plants().isEmpty()) return;
        for (int lz = 0; lz < h.oz; lz++) {
            for (int lx = 0; lx < ZoneRules.CELL; lx++) {
                if (lx == h.ox + door || lx == h.ox + door + 1 && door > 0 || rng.nextFloat() > 0.18f) continue;
                h.putCell(lx, 0, lz, p.plants().get(rng.nextInt(p.plants().size())).defaultBlockState());
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
        // Scaffolding stands at the sides of the building while it goes up.
        List<BlockPos> scaffold = List.of(h.pos(-1, 0, 1), h.pos(h.w, 0, 1), h.pos(-1, 0, h.d - 2), h.pos(h.w, 0, h.d - 2));
        return new Plan(List.copyOf(h.steps), new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), households, jobs, kind, scaffold);
    }

    private BuildingGenerator() {}
}
