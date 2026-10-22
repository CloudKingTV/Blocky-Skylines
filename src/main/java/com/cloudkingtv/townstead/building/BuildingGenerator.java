package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.service.ServiceType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Procedural village buildings, built to look like a lived-in place rather than boxes. Every building is composed
 * on its whole lot (one to four 8x8 cells): houses are main blocks with wings, porches and lean-tos under gable or
 * hipped roofs with real eaves, dormers and chimneys; walls are timber-framed with a stone plinth, shuttered windows
 * and jettied upper floors; lots get front gardens, hedges, fences with gates, trees, vegetable plots and lamps.
 * Levels add wings, upper floors under the roof, porches and gardens, so towns spread and fill in instead of only
 * growing taller. Everything is deterministic from the building record (seed, lot, level, biome style).
 */
public final class BuildingGenerator {
    /** One construction step; a door's upper half rides along with its lower half. */
    public record Step(BlockPos pos, BlockState state) {}

    /** {@code dig}: ground to clear when the site is levelled (a quarry's pit), below the floor. */
    public record Plan(List<Step> steps, BlockPos min, BlockPos max, int households, int jobs, String kind, List<BlockPos> scaffold,
                       List<BlockPos> dig) {
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

    /** Build order: ground work, then the frame, the roof, fittings that hang on them, and the garden last. */
    static final int GROUND = 0, FRAME = 1, ROOF = 2, FIT = 3, GARDEN = 4;
    /** Storey height: three blocks of wall and a beam that is the next floor. */
    static final int STOREY = 4;

    public static Plan plan(Building b) {
        return plan(b, b.level);
    }

    /** The plan at a given level (upgrades compare the old level's plan with the new one). */
    public static Plan plan(Building b, int level) {
        Canvas c = new Canvas(b, Math.max(1, Math.min(Building.MAX_LEVEL, level)));
        String kind = switch (b.zone) {
            case RESIDENTIAL -> Homes.house(c);
            case RESIDENTIAL_HIGH -> Homes.terrace(c);
            case COMMERCIAL -> "leisure".equals(b.spec) ? Trade.tavern(c) : Trade.shop(c);
            case COMMERCIAL_HIGH -> "leisure".equals(b.spec) ? Trade.tavern(c) : Trade.market(c);
            case INDUSTRIAL -> switch (b.spec) {
                case "farming" -> Trade.farm(c);
                case "quarry" -> Trade.quarry(c);
                case "forestry" -> Trade.lumberYard(c);
                default -> Trade.workshop(c);
            };
            case OFFICE -> Trade.guildHall(c);
            case CIVIC -> Civic.build(c, b.serviceType());
        };
        return c.finish(kind);
    }

    // ================================================================ the canvas

    /** A footprint in lot coordinates (inclusive). */
    record Box(int x0, int z0, int x1, int z1) {
        int w() { return x1 - x0 + 1; }
        int d() { return z1 - z0 + 1; }
        int cx() { return (x0 + x1) / 2; }
        int cz() { return (z0 + z1) / 2; }
        boolean edge(int x, int z) { return x == x0 || x == x1 || z == z0 || z == z1; }
        boolean corner(int x, int z) { return (x == x0 || x == x1) && (z == z0 || z == z1); }
        boolean contains(int x, int z) { return x >= x0 && x <= x1 && z >= z0 && z <= z1; }
        Box grow(int n) { return new Box(x0 - n, z0 - n, x1 + n, z1 + n); }
        Box front(int n) { return new Box(x0, z0 - n, x1, z1); }
    }

    /** Wall dressing: posts, beams, infill below and above, a stone plinth, windows and their shutters. */
    record Wall(BlockState post, Block beam, BlockState lower, BlockState upper, BlockState plinth, Block shutter, boolean framed, boolean tall) {
        Wall with(BlockState lower, BlockState upper) { return new Wall(post, beam, lower, upper, plinth, shutter, framed, tall); }
    }

    /** The lot as a drawing surface: x along the road (left to right seen from it), z back from the road, y up from the floor. */
    static final class Canvas {
        final Building b;
        final Palette p;
        final RandomSource rng;
        final int level, W, D, zf;
        final Direction front, back, right, left;
        private final int ox, oz;
        private final Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        private final Map<BlockPos, int[]> meta = new HashMap<>();
        private int order;
        /** Masses already built, for clipping lower roofs where they run into taller walls: {x0, z0, x1, z1, top}. */
        private final List<int[]> masses = new ArrayList<>();
        private final java.util.Set<Long> doorways = new java.util.HashSet<>();
        final List<BlockPos> scaffold = new ArrayList<>();
        /** Ground dug out when the site is levelled. */
        final List<BlockPos> dig = new ArrayList<>();
        int households, jobs;
        /** Rooms the walls enclose: {x0, z0, x1, z1, y0, storeys, open (1: no floors inside, like a nave)}. */
        final List<int[]> rooms = new ArrayList<>();
        /** Standing spots kept clear for stairs and landings: key(x, y, z). */
        private final java.util.Set<Long> keepClear = new java.util.HashSet<>();
        /** Whether finishing fills the rooms with stairs and furniture (bespoke landmarks do their own). */
        boolean furnish = true;

        Canvas(Building b, int level) {
            this.b = b;
            this.level = level;
            this.p = Palette.of(b.style);
            this.rng = RandomSource.create(b.seed);
            this.W = 8 * Math.max(1, b.wide);
            this.D = 8 * Math.max(1, b.deep);
            this.zf = Math.min(b.setback, 3);
            front = b.front;
            back = front.getOpposite();
            right = front.getCounterClockWise(); // the right hand of someone on the road looking at the lot
            left = right.getOpposite();
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (long k : b.cellKeys()) {
                int cx = com.cloudkingtv.townstead.road.RoadRaster.keyX(k), cz = com.cloudkingtv.townstead.road.RoadRaster.keyZ(k);
                x0 = Math.min(x0, cx * 8); z0 = Math.min(z0, cz * 8);
                x1 = Math.max(x1, cx * 8 + 7); z1 = Math.max(z1, cz * 8 + 7);
            }
            // The front-left corner: on the front edge, at the left end.
            ox = front.getStepX() < 0 ? x0 : front.getStepX() > 0 ? x1 : right.getStepX() > 0 ? x0 : x1;
            oz = front.getStepZ() < 0 ? z0 : front.getStepZ() > 0 ? z1 : right.getStepZ() > 0 ? z0 : z1;
        }

        BlockPos at(int x, int y, int z) {
            return new BlockPos(ox + right.getStepX() * x + back.getStepX() * z, b.baseY + y, oz + right.getStepZ() * x + back.getStepZ() * z);
        }

        /** Local direction: (1,0) right, (-1,0) left, (0,1) back, (0,-1) toward the road. */
        Direction dir(int dx, int dz) {
            return dx > 0 ? right : dx < 0 ? left : dz > 0 ? back : front;
        }

        boolean inLot(int x, int z) { return x >= 0 && x < W && z >= zf && z < D; }

        void put(int x, int y, int z, BlockState s, int phase) {
            BlockPos pos = at(x, y, z);
            states.put(pos, s);
            int[] m = meta.get(pos);
            if (m == null) meta.put(pos, new int[] {phase, order++});
            else m[0] = Math.max(m[0], phase);
        }

        void put(int x, int y, int z, Block block, int phase) { put(x, y, z, block.defaultBlockState(), phase); }

        /** Ground to dig out (nothing is built there). */
        void dig(int x, int y, int z) {
            BlockPos pos = at(x, y, z);
            dig.add(pos);
            states.remove(pos);
            meta.remove(pos);
        }

        /** Only where nothing is planned yet. */
        void soft(int x, int y, int z, BlockState s, int phase) { if (!has(x, y, z)) put(x, y, z, s, phase); }

        boolean has(int x, int y, int z) { return states.containsKey(at(x, y, z)); }

        BlockState get(int x, int y, int z) { return states.get(at(x, y, z)); }

        void erase(int x, int y, int z) {
            BlockPos pos = at(x, y, z);
            states.remove(pos);
            meta.remove(pos);
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s, int phase) {
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) put(x, y, z, s, phase);
        }

        // ---------------------------------------------------------------- block states

        BlockState stairs(Block block, Direction facing, boolean top) {
            return block.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, top ? Half.TOP : Half.BOTTOM);
        }

        BlockState slab(Block block, SlabType type) { return block.defaultBlockState().setValue(SlabBlock.TYPE, type); }

        BlockState log(Block block, Direction.Axis axis) {
            BlockState s = block.defaultBlockState();
            return s.hasProperty(RotatedPillarBlock.AXIS) ? s.setValue(RotatedPillarBlock.AXIS, axis) : s;
        }

        /** A horizontal log along local x or z. */
        BlockState beam(Block block, boolean alongX) { return log(block, alongX ? right.getAxis() : back.getAxis()); }

        BlockState facing(Block block, Direction d) {
            BlockState s = block.defaultBlockState();
            if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return s.setValue(BlockStateProperties.HORIZONTAL_FACING, d);
            if (s.hasProperty(BlockStateProperties.FACING)) return s.setValue(BlockStateProperties.FACING, d);
            return s;
        }

        BlockState lantern(boolean hanging) { return Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, hanging); }

        BlockState leaves(Block block) {
            BlockState s = block.defaultBlockState();
            return s.hasProperty(LeavesBlock.PERSISTENT) ? s.setValue(LeavesBlock.PERSISTENT, true) : s;
        }

        int pick(int n) { return rng.nextInt(n); }

        <T> T pick(List<T> list) { return list.get(rng.nextInt(list.size())); }

        // ---------------------------------------------------------------- walls

        /** A foundation under the box and a floor inside it, at y = -1 (ground level). */
        void foundation(Box box, BlockState edge, BlockState floor) {
            for (int z = box.z0; z <= box.z1; z++) for (int x = box.x0; x <= box.x1; x++) put(x, -1, z, box.edge(x, z) ? edge : floor, GROUND);
        }

        /** A doorway in a wall, cut when the walls go up; the door itself is fitted later. */
        void doorway(int x, int y, int z) { doorways.add(key(x, y, z)); }

        private static long key(int x, int y, int z) { return ((long) x & 0xFFFF) | (((long) z & 0xFFFF) << 16) | (((long) y & 0xFFFF) << 32); }

        /**
         * Walls of a box for {@code storeys} storeys from y0, with corner posts, timber framing on long walls, windows
         * (with shutters) and a beam ring that carries the floor above. Returns the y of the top beam (the roof base).
         */
        int walls(Box box, int y0, int storeys, Wall w, BlockState floor) {
            for (int s = 0; s < storeys; s++) {
                int base = y0 + s * STOREY;
                BlockState infill = s == 0 && y0 == 0 ? w.lower() : w.upper();
                for (int x = box.x0; x <= box.x1; x++) for (int z = box.z0; z <= box.z1; z++) {
                    if (!box.edge(x, z)) continue;
                    boolean alongX = z == box.z0 || z == box.z1;
                    int i = alongX ? x - box.x0 : z - box.z0, len = alongX ? box.w() : box.d();
                    int door = s == 0 && y0 == 0 ? doorOnWall(box, alongX, alongX ? z : x) : -1;
                    boolean post = box.corner(x, z) || w.framed() && door < 0 && postAt(i, len);
                    boolean window = !post && (door < 0 ? windowAt(i, len) : beside(i, door, len));
                    for (int y = base; y < base + 3; y++) {
                        if (doorways.contains(key(x, y, z)) || doorways.contains(key(x, y - 1, z)) && y == base + 1) continue;
                        BlockState st;
                        if (post) st = w.post();
                        else if (s == 0 && y0 == 0 && y == base && w.plinth() != null) st = w.plinth();
                        else if (window && (y == base + 1 || w.tall() && y == base + 2 && s == 0)) st = Blocks.GLASS_PANE.defaultBlockState();
                        else st = infill;
                        put(x, y, z, st, FRAME);
                    }
                    if (window && w.shutter() != null && !doorways.contains(key(x, base, z))) shutters(box, x, base + 1, z);
                }
                // Beam ring and the floor above.
                int by = base + 3;
                for (int x = box.x0; x <= box.x1; x++) for (int z = box.z0; z <= box.z1; z++) {
                    if (box.edge(x, z)) put(x, by, z, box.corner(x, z) ? w.post() : beam(w.beam(), z == box.z0 || z == box.z1), FRAME);
                    else if (s + 1 < storeys) put(x, by, z, floor, FRAME);
                }
            }
            int top = y0 + storeys * STOREY - 1;
            masses.add(new int[] {box.x0, box.z0, box.x1, box.z1, top});
            rooms.add(new int[] {box.x0, box.z0, box.x1, box.z1, y0, storeys, floor.isAir() ? 1 : 0});
            return top;
        }

        /** Position along a wall of a doorway in it (ground floor), or -1. */
        private int doorOnWall(Box box, boolean alongX, int fixed) {
            int len = alongX ? box.w() : box.d();
            for (int i = 1; i < len - 1; i++) {
                int x = alongX ? box.x0 + i : fixed, z = alongX ? fixed : box.z0 + i;
                if (doorways.contains(key(x, 0, z))) return i;
            }
            return -1;
        }

        /** Windows on a wall with a door: either side of it on a short wall, in pairs stepping away from it on a long one. */
        static boolean beside(int i, int door, int len) {
            if (i <= 0 || i >= len - 1 || i == door) return false;
            int d = Math.abs(i - door);
            return len <= 7 ? d == 1 || d == 3 : d >= 2 && d % 2 == 0;
        }

        /** Windows spaced evenly and symmetrically along a wall: one per three blocks of wall. */
        static boolean windowAt(int i, int len) {
            int n = len - 2;
            if (i <= 0 || i >= len - 1 || n <= 0) return false;
            int count = Math.max(1, (n + 1) / 3);
            for (int k = 0; k < count; k++) {
                int pos = 1 + (int) Math.floor((k + 0.5) * n / count);
                if (pos == i || n % 2 == 0 && count == 1 && n >= 4 && pos - 1 == i) return true;
            }
            return false;
        }

        /** Timber framing: a post halfway between neighbouring windows on walls long enough to need one. */
        static boolean postAt(int i, int len) {
            if (len < 7 || windowAt(i, len)) return false;
            int n = len - 2, count = Math.max(1, (n + 1) / 3);
            for (int k = 0; k + 1 < count; k++) {
                int a = 1 + (int) Math.floor((k + 0.5) * n / count), b = 1 + (int) Math.floor((k + 1.5) * n / count);
                if (i == (a + b) / 2 && b - a >= 2) return true;
            }
            return false;
        }

        /** Open trapdoors either side of a window, flat against the outside of the wall. */
        private void shutters(Box box, int x, int y, int z) {
            int dx = x == box.x0 ? -1 : x == box.x1 ? 1 : 0, dz = z == box.z0 ? -1 : z == box.z1 ? 1 : 0;
            if (dx != 0 && dz != 0) return;
            Direction out = dir(dx, dz);
            int sx = dz != 0 ? 1 : 0, sz = dx != 0 ? 1 : 0; // along the wall
            Block t = shutterBlock();
            for (int side = -1; side <= 1; side += 2) {
                int px = x + dx + sx * side, pz = z + dz + sz * side;
                if (has(px, y, pz)) continue;
                // Never across a doorway: the shutter beside a window next to the door would hang in front of it.
                if (doorways.contains(key(px - dx, 0, pz - dz)) || doorways.contains(key(px - dx, y - 1, pz - dz))) continue;
                put(px, y, pz, t.defaultBlockState().setValue(TrapDoorBlock.FACING, out).setValue(TrapDoorBlock.OPEN, true)
                        .setValue(TrapDoorBlock.HALF, Half.TOP), FIT);
            }
        }

        Block shutterBlock() { return Style.trapdoor(p); }

        /** A door in a wall (facing into the building) with the doorway kept clear. */
        void door(int x, int z, Direction inward, DoorHingeSide hinge) {
            put(x, 0, z, Style.door(p).defaultBlockState().setValue(DoorBlock.FACING, inward).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
                    .setValue(DoorBlock.HINGE, hinge), FIT);
        }

        // ---------------------------------------------------------------- roofs

        /** A roof block, unless it would end up inside a taller mass built before. */
        void roofPut(int x, int y, int z, BlockState s, int[] self) {
            for (int[] m : masses) {
                if (m == self) continue;
                if (x >= m[0] && x <= m[2] && z >= m[1] && z <= m[3] && y <= m[4]) return;
            }
            put(x, y, z, s, ROOF);
        }

        private int[] massOf(Box box) {
            for (int[] m : masses) if (m[0] == box.x0 && m[1] == box.z0 && m[2] == box.x1 && m[3] == box.z1) return m;
            return new int[] {box.x0, box.z0, box.x1, box.z1, Integer.MIN_VALUE};
        }

        /**
         * A gable roof over a box from its top beam: ridge along x (eaves to front and back) or along z (gable to the
         * road), one block of eave all round, gable ends filled and given an attic window. Returns the ridge y.
         */
        int gable(Box box, int y, boolean alongX, Block stair, Block slab, BlockState gableFill) {
            int[] self = massOf(box);
            int a0 = alongX ? box.z0 : box.x0, a1 = alongX ? box.z1 : box.x1; // across the slope
            int l0 = (alongX ? box.x0 : box.z0) - 1, l1 = (alongX ? box.x1 : box.z1) + 1; // along the ridge
            Direction up0 = alongX ? back : right, up1 = alongX ? front : left;
            int top = y;
            for (int k = 0; ; k++) {
                int s0 = a0 - 1 + k, s1 = a1 + 1 - k;
                if (s0 > s1) break;
                int yy = y + k;
                top = yy;
                for (int l = l0; l <= l1; l++) {
                    if (s0 == s1) roofPut(alongX ? l : s0, yy, alongX ? s0 : l, slab(slab, SlabType.BOTTOM), self);
                    else {
                        roofPut(alongX ? l : s0, yy, alongX ? s0 : l, stairs(stair, up0, false), self);
                        roofPut(alongX ? l : s1, yy, alongX ? s1 : l, stairs(stair, up1, false), self);
                    }
                }
                if (k >= 1) for (int s = s0 + 1; s < s1; s++) {
                    for (int end : new int[] {l0 + 1, l1 - 1}) roofPut(alongX ? end : s, yy, alongX ? s : end, gableFill, self);
                }
            }
            int height = top - y;
            if (height >= 3) {
                int mid = (a0 + a1) / 2;
                for (int end : new int[] {l0 + 1, l1 - 1}) roofPut(alongX ? end : mid, y + 2, alongX ? mid : end, Blocks.GLASS_PANE.defaultBlockState(), self);
            }
            return top;
        }

        /** A hipped roof: slopes on all four sides, a short ridge or a point on top. Returns the top y. */
        int hip(Box box, int y, Block stair, Block slab) {
            int[] self = massOf(box);
            int top = y;
            for (int k = 0; ; k++) {
                int x0 = box.x0 - 1 + k, x1 = box.x1 + 1 - k, z0 = box.z0 - 1 + k, z1 = box.z1 + 1 - k;
                if (x0 > x1 || z0 > z1) break;
                int yy = y + k;
                top = yy;
                if (x0 == x1 || z0 == z1) {
                    for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) roofPut(x, yy, z, slab(slab, SlabType.BOTTOM), self);
                    break;
                }
                for (int x = x0; x <= x1; x++) {
                    roofPut(x, yy, z0, stairs(stair, back, false), self);
                    roofPut(x, yy, z1, stairs(stair, front, false), self);
                }
                for (int z = z0 + 1; z < z1; z++) {
                    roofPut(x0, yy, z, stairs(stair, right, false), self);
                    roofPut(x1, yy, z, stairs(stair, left, false), self);
                }
                if (x1 - x0 <= 1 || z1 - z0 <= 1) {
                    for (int x = x0 + 1; x < x1; x++) for (int z = z0 + 1; z < z1; z++) roofPut(x, yy, z, slab(slab, SlabType.BOTTOM), self);
                }
            }
            return top;
        }

        /** A flat roof with a parapet and corner posts (desert towns, guild terraces). Returns the parapet y. */
        int flat(Box box, int y, BlockState deck, BlockState parapet, BlockState cornerCap) {
            for (int x = box.x0; x <= box.x1; x++) for (int z = box.z0; z <= box.z1; z++) {
                if (!box.edge(x, z)) put(x, y, z, deck, ROOF);
                else put(x, y + 1, z, box.corner(x, z) ? cornerCap : parapet, ROOF);
            }
            return y + 1;
        }

        /** The roof the style uses over a box (flat in the desert, with a shaded terrace). */
        int roof(Box box, int y, boolean alongX, BlockState gableFill) {
            if (p.flatRoof()) {
                int top = flat(box, y, p.stone().defaultBlockState(), Style.wall(p).defaultBlockState(), p.trim().defaultBlockState());
                if (box.w() >= 4 && box.d() >= 4) {
                    // A cloth canopy on poles over part of the roof, and potted plants along the parapet.
                    DyeColor cloth = new DyeColor[] {DyeColor.WHITE, DyeColor.ORANGE, DyeColor.BLUE, DyeColor.RED}[(int) Math.floorMod(b.seed >>> 9, 4L)];
                    int cx0 = box.x0 + 1, cx1 = Math.min(box.x1 - 1, box.x0 + 3), cz0 = box.z1 - 3, cz1 = box.z1 - 1;
                    for (int x = cx0; x <= cx1; x++) for (int z = cz0; z <= cz1; z++) {
                        boolean pole = (x == cx0 || x == cx1) && (z == cz0 || z == cz1);
                        if (pole) { put(x, y + 1, z, Style.fence(p), ROOF); put(x, y + 2, z, Style.fence(p), ROOF); }
                        put(x, y + 3, z, slab(Blocks.WOOL_SLAB.pick(cloth), SlabType.BOTTOM), ROOF);
                    }
                    put(box.x1 - 1, y + 1, box.z0 + 1, Blocks.POTTED_CACTUS, FIT);
                    put(box.x0 + 1, y + 1, box.z0 + 1, Blocks.POTTED_DEAD_BUSH, FIT);
                }
                return top + 2;
            }
            Block[] r = Style.roof(p, b.seed);
            return gable(box, y, alongX, r[0], r[1], gableFill);
        }

        /** A dormer on the front slope of an along-x gable roof: cheeks, a window and its own little roof. */
        void dormer(Box box, int y, int cx, BlockState wall) {
            if (p.flatRoof()) return;
            Block[] r = Style.roof(p, b.seed);
            int z0 = box.z0;
            for (int z = z0; z <= z0 + 1; z++) {
                for (int yy = y + 1; yy <= y + 2; yy++) {
                    put(cx - 1, yy, z, wall, ROOF);
                    put(cx + 1, yy, z, wall, ROOF);
                }
            }
            put(cx, y + 1, z0, Blocks.GLASS_PANE.defaultBlockState(), ROOF);
            put(cx, y + 2, z0, wall, ROOF);
            for (int z = z0 - 1; z <= z0 + 2; z++) {
                put(cx - 2, y + 2, z, stairs(r[0], right, false), ROOF);
                put(cx + 2, y + 2, z, stairs(r[0], left, false), ROOF);
                put(cx - 1, y + 3, z, stairs(r[0], right, false), ROOF);
                put(cx + 1, y + 3, z, stairs(r[0], left, false), ROOF);
                put(cx, y + 3, z, wall, ROOF);
                put(cx, y + 4, z, slab(r[1], SlabType.BOTTOM), ROOF);
            }
            erase(cx, y + 1, z0 + 1);
        }

        /** A brick chimney up the outside of a wall, with a smoking campfire on top. */
        void chimney(int x, int z, int top) {
            BlockState brick = Style.chimney(p).defaultBlockState();
            for (int y = -1; y <= top; y++) put(x, y, z, brick, FRAME);
            put(x, top + 1, z, Blocks.CAMPFIRE, FIT);
        }

        // ---------------------------------------------------------------- outside

        /** Upside-down stairs under an overhang (a jettied floor or a deep eave). */
        void brackets(Box box, int y, int every) {
            for (int x = box.x0 + 1; x < box.x1; x += every) put(x, y, box.z0 - 1, stairs(Style.stairsOf(p), back, true), FIT);
        }

        /** A hood over a front door: slabs on brackets, and a lantern under it (a cloth canopy in the desert). */
        void doorHood(int x, int z) {
            if (p.flatRoof()) {
                DyeColor cloth = new DyeColor[] {DyeColor.WHITE, DyeColor.ORANGE, DyeColor.BLUE, DyeColor.RED}[(int) Math.floorMod(b.seed >>> 9, 4L)];
                for (int dx = -1; dx <= 1; dx++) put(x + dx, 2, z - 1, stairs(Blocks.WOOL_STAIRS.pick(cloth), back, false), FIT);
                put(x - 1, 1, z - 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, front), FIT);
                return;
            }
            Block slabB = Style.roof(p, b.seed)[1];
            for (int dx = -1; dx <= 1; dx++) put(x + dx, 2, z - 1, slab(slabB, SlabType.BOTTOM), FIT);
            put(x + 1, 1, z - 1, lantern(true), FIT);
        }

        /** A veranda along the front of a box: plank floor, fence posts, a slab roof, lanterns. */
        void porch(Box box, int depth) {
            Block slabB = Style.roof(p, b.seed)[1];
            int z0 = box.z0 - depth;
            for (int x = box.x0; x <= box.x1; x++) for (int z = z0; z < box.z0; z++) {
                put(x, -1, z, Style.floor(p).defaultBlockState(), GROUND);
                boolean postHere = z == z0 && (x == box.x0 || x == box.x1 || (x - box.x0) % 3 == 0);
                if (postHere) for (int y = 0; y <= 1; y++) put(x, y, z, Style.fence(p).defaultBlockState(), FRAME);
                put(x, 2, z, slab(slabB, SlabType.BOTTOM), ROOF);
            }
            put(box.x0 + 1, 1, z0, lantern(true), FIT);
            put(box.x1 - 1, 1, z0, lantern(true), FIT);
        }

        /** A garden path of dirt path blocks from the road edge to a door. */
        void path(int x, int fromZ, int toZ) {
            for (int z = fromZ; z <= toZ; z++) if (!has(x, -1, z)) put(x, -1, z, Blocks.DIRT_PATH, GROUND);
        }

        /** A small tree: a trunk and a rounded crown. */
        void tree(int x, int z, int height) {
            Block logB = Style.treeLog(p), leafB = Style.treeLeaves(p);
            for (int y = 0; y < height; y++) put(x, y, z, logB, GARDEN);
            BlockState leaf = leaves(leafB);
            for (int dy = -1; dy <= 1; dy++) {
                int r = dy == 1 ? 1 : 2;
                for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) == r && Math.abs(dz) == r && (r == 2 || dy == 1)) continue;
                    if (!inLot(x + dx, z + dz) || dx == 0 && dz == 0 && dy < 1) continue;
                    soft(x + dx, height - 1 + dy, z + dz, leaf, GARDEN);
                }
            }
            soft(x, height + 1, z, leaf, GARDEN);
        }

        /** Flowers (or desert shrubs) scattered over a patch, leaving room to walk. */
        void flowers(int x0, int z0, int x1, int z1, float density) {
            if (p.plants().isEmpty()) return;
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                if (!inLot(x, z) || has(x, 0, z) || has(x, -1, z) || rng.nextFloat() > density) continue;
                put(x, 0, z, pick(p.plants()), GARDEN);
            }
        }

        /** A low hedge (leaves) along x at z, skipping where something stands. */
        void hedge(int x0, int x1, int z) {
            BlockState leaf = leaves(Style.hedge(p));
            for (int x = x0; x <= x1; x++) if (inLot(x, z) && !has(x, 0, z) && !has(x, -1, z)) put(x, 0, z, leaf, GARDEN);
        }

        /** A fence round part of the lot, with a gate where the path comes in. */
        void fenceRun(int x0, int z0, int x1, int z1, int gateX, int gateZ) {
            BlockState fence = Style.fence(p).defaultBlockState();
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                boolean edge = x == x0 || x == x1 || z == z0 || z == z1;
                if (!edge || !inLot(x, z) || has(x, 0, z)) continue;
                if (x == gateX && z == gateZ) {
                    Direction across = z == z0 || z == z1 ? front : right;
                    put(x, 0, z, facing(Style.gate(p), across), GARDEN);
                } else put(x, 0, z, fence, GARDEN);
            }
        }

        /** A lamp post: fence posts with a lantern on top. */
        void lampPost(int x, int z) {
            put(x, 0, z, Style.fence(p), GARDEN);
            put(x, 1, z, Style.fence(p), GARDEN);
            put(x, 2, z, lantern(false), GARDEN);
        }

        /** A bench (stairs with arms) along x, facing toward {@code faces}. */
        void bench(int x0, int x1, int z, Direction faces) {
            for (int x = x0; x <= x1; x++) put(x, 0, z, stairs(Style.stairsOf(p), faces.getOpposite(), false), GARDEN);
        }

        /** A vegetable plot: farmland round a water hole, crops ripe. */
        void plot(int x0, int z0, int x1, int z1) {
            int wx = (x0 + x1) / 2, wz = (z0 + z1) / 2;
            Block[] crops = {Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS};
            Block crop = crops[pick(crops.length)];
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
                if (!inLot(x, z)) continue;
                if (x == wx && z == wz) { put(x, -1, z, Blocks.WATER, GROUND); continue; }
                put(x, -1, z, Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7), GROUND);
                BlockState c = crop.defaultBlockState();
                if (crop instanceof CropBlock cb) c = cb.getStateForAge(cb.getMaxAge());
                put(x, 0, z, c, GARDEN);
            }
        }

        /** A firewood or lumber stack. */
        void woodpile(int x0, int x1, int z, int height) {
            for (int x = x0; x <= x1; x++) for (int y = 0; y < height; y++) put(x, y, z, beam(Style.treeLog(p), true), GARDEN);
        }

        /** A hanging wall banner, the colour of a trade or a service. */
        void banner(int x, int y, int z, DyeColor colour, Direction out) {
            put(x, y, z, facing(Blocks.WALL_BANNER.pick(colour), out), FIT);
        }

        // ---------------------------------------------------------------- inside

        /**
         * Makes every room liveable: a floor under each upper storey, a staircase (or a ladder where there's no room
         * for one) between storeys, and furniture by kind along the walls, clear of doors, stairs and landings.
         */
        void furnishRooms() {
            BlockState floor = p.floor().defaultBlockState();
            for (int[] r : rooms) {
                if (r[6] == 1) continue;
                Box box = new Box(r[0], r[1], r[2], r[3]);
                if (box.w() < 4 || box.d() < 4) continue;
                if (r[4] > 0) {
                    // An upper storey built as its own box (a jettied floor): floor it, and a way up from the room below.
                    for (int[] o : rooms) {
                        if (o == r || o[6] == 1 || o[4] + o[5] * STOREY != r[4]) continue;
                        Box shared = new Box(Math.max(r[0], o[0]), Math.max(r[1], o[1]), Math.min(r[2], o[2]), Math.min(r[3], o[3]));
                        if (shared.w() < 4 || shared.d() < 4) continue;
                        for (int x = box.x0 + 1; x < box.x1; x++) for (int z = box.z0 + 1; z < box.z1; z++) soft(x, r[4] - 1, z, floor, FRAME);
                        climb(shared, r[4] - STOREY);
                        break;
                    }
                }
                for (int st = 1; st < r[5]; st++) climb(box, r[4] + (st - 1) * STOREY);
            }
            for (int[] r : rooms) {
                if (r[6] == 1) continue;
                Box box = new Box(r[0], r[1], r[2], r[3]);
                if (box.w() < 4 || box.d() < 4) continue;
                boolean roomAbove = false;
                for (int[] o : rooms) {
                    if (o != r && o[6] == 0 && o[4] == r[4] + r[5] * STOREY && o[0] <= r[2] && o[2] >= r[0] && o[1] <= r[3] && o[3] >= r[1]) roomAbove = true;
                }
                for (int st = 0; st < r[5]; st++) furnishStorey(box, r[4] + st * STOREY, r[4] + st * STOREY == 0, st == r[5] - 1 && !roomAbove);
            }
        }

        /** Free for something to stand: nothing planned there or just above. */
        private boolean clear(int x, int y, int z) {
            return !has(x, y, z) && !has(x, y + 1, z) && !keepClear.contains(key(x, y, z));
        }

        /** Planned empty, or only the floor of the storey above (which a stairwell may cut). */
        private boolean openOrFloor(int x, int y, int z, BlockState floor) {
            BlockState s = get(x, y, z);
            return s == null || s.isAir() || s == floor;
        }

        /** The inside spot just through a doorway at this standing level, or none. */
        private boolean byDoor(Box box, int x, int y, int z) {
            for (int[] d : new int[][] {{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                if (doorways.contains(key(x + d[0], y, z + d[1])) && box.edge(x + d[0], z + d[1])) return true;
            }
            return false;
        }

        /** Stairs from {@code base} up to the storey above, along a back or side wall; a ladder in a corner if they don't fit. */
        void climb(Box box, int base) {
            BlockState floor = p.floor().defaultBlockState();
            int ix0 = box.x0 + 1, ix1 = box.x1 - 1, iz0 = box.z0 + 1, iz1 = box.z1 - 1;
            // Runs: start x, start z, step x, step z (up the stairs), back wall first.
            int[][] runs = {{ix0, iz1, 1, 0}, {ix1, iz1, -1, 0}, {ix0, iz1, 0, -1}, {ix1, iz1, 0, -1}, {ix0, iz0, 0, 1}, {ix1, iz0, 0, 1}};
            for (int[] run : runs) {
                int sx = run[0], sz = run[1], dx = run[2], dz = run[3];
                int lx = sx + 4 * dx, lz = sz + 4 * dz;
                if (!box.contains(lx, lz) || box.edge(lx, lz)) continue;
                // Step on from the end of the run, or from the room side of the first step (a stair takes either).
                int ax = sx - dx, az = sz - dz;
                if (box.edge(ax, az) || !box.contains(ax, az)) {
                    int ix = Integer.signum(box.cx() - sx), iz = Integer.signum(box.cz() - sz);
                    ax = sx + (dx == 0 ? ix : 0);
                    az = sz + (dz == 0 ? iz : 0);
                }
                if (!box.contains(ax, az) || box.edge(ax, az)) continue;
                boolean ok = clear(ax, base, az) && !byDoor(box, ax, base, az) && clear(lx, base + STOREY, lz);
                for (int k = 0; k < 4 && ok; k++) {
                    int x = sx + k * dx, z = sz + k * dz;
                    if (box.edge(x, z) || byDoor(box, x, base, z)) { ok = false; break; }
                    for (int y = base + k; y <= base + k + 2 && ok; y++) {
                        if (y == base + 3 ? !openOrFloor(x, y, z, floor) : has(x, y, z)) ok = false;
                    }
                    if (keepClear.contains(key(x, base, z))) ok = false;
                }
                if (!ok) continue;
                Direction up = dir(dx, dz);
                for (int k = 0; k < 4; k++) {
                    int x = sx + k * dx, z = sz + k * dz;
                    put(x, base + k, z, stairs(Style.stairsOf(p), up, false), FIT);
                    if (k == 1 || k == 2) erase(x, base + 3, z);
                    keepClear.add(key(x, base, z));
                    keepClear.add(key(x, base + STOREY, z));
                }
                keepClear.add(key(ax, base, az));
                keepClear.add(key(lx, base + STOREY, lz));
                return;
            }
            // A ladder against a solid stretch of wall in a corner.
            int[][] corners = {{ix0, iz1, 0, 1}, {ix1, iz1, 0, 1}, {ix0, iz0, -1, 0}, {ix1, iz0, 1, 0}, {ix0, iz1, -1, 0}, {ix1, iz1, 1, 0}};
            for (int[] c : corners) {
                int x = c[0], z = c[1], wx = x + c[2], wz = z + c[3];
                boolean ok = !byDoor(box, x, base, z);
                for (int y = base; y <= base + 3 && ok; y++) {
                    if (y == base + 3 ? !openOrFloor(x, y, z, floor) : has(x, y, z)) ok = false;
                    BlockState wall = get(wx, y, wz);
                    if (wall == null || wall.isAir() || wall.is(Blocks.GLASS_PANE) || wall.getBlock() instanceof DoorBlock
                            || !wall.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) ok = false;
                }
                if (!ok || keepClear.contains(key(x, base, z))) continue;
                Direction away = dir(-c[2], -c[3]);
                for (int y = base; y <= base + 3; y++) put(x, y, z, facing(Blocks.LADDER, away), FIT);
                keepClear.add(key(x, base, z));
                keepClear.add(key(x, base + STOREY, z));
                keepClear.add(key(x - c[2], base, z - c[3]));
                return;
            }
        }

        /** Furniture for one storey: what a family, a shopkeeper, a craftsman or a clerk would have, along the walls. */
        void furnishStorey(Box box, int base, boolean ground, boolean top) {
            int ix0 = box.x0 + 1, ix1 = box.x1 - 1, iz0 = box.z0 + 1, iz1 = box.z1 - 1;
            // Spots along the walls, each with the direction of its wall.
            List<int[]> spots = new ArrayList<>();
            for (int x = ix0; x <= ix1; x++) for (int z = iz0; z <= iz1; z++) {
                int wx = x == ix0 ? -1 : x == ix1 ? 1 : 0, wz = z == iz0 ? -1 : z == iz1 ? 1 : 0;
                if (wx == 0 && wz == 0) continue;
                if (wx != 0 && wz != 0) wx = 0; // corners: against the front or back wall
                if (!clear(x, base, z) || byDoor(box, x, base, z)) continue;
                spots.add(new int[] {x, z, wx, wz});
            }
            java.util.Collections.shuffle(spots, new java.util.Random(b.seed * 31 + base * 7L + box.x0 * 13L + box.z0));
            boolean home = b.zone.residential() || !ground && (b.zone == com.cloudkingtv.townstead.zone.ZoneType.COMMERCIAL || b.zone == com.cloudkingtv.townstead.zone.ZoneType.COMMERCIAL_HIGH);
            List<BlockState> items = new ArrayList<>();
            int beds = 0;
            if (home) {
                if (ground) items.addAll(List.of(facing(Blocks.FURNACE, front), Blocks.CRAFTING_TABLE.defaultBlockState(), Blocks.BARREL.defaultBlockState()));
                if (!ground || top) {
                    beds = Math.min(2, Math.max(1, households / Math.max(1, rooms.size())));
                    items.addAll(List.of(Blocks.CHEST.defaultBlockState(), Blocks.BOOKSHELF.defaultBlockState(), Blocks.POTTED_POPPY.defaultBlockState()));
                }
            } else {
                switch (b.zone) {
                    case COMMERCIAL, COMMERCIAL_HIGH -> items.addAll(List.of(Blocks.BARREL.defaultBlockState(), Blocks.CHEST.defaultBlockState(),
                            Blocks.BARREL.defaultBlockState(), Blocks.CHEST.defaultBlockState()));
                    case INDUSTRIAL -> items.addAll(List.of(Blocks.CRAFTING_TABLE.defaultBlockState(), Blocks.BARREL.defaultBlockState(),
                            Blocks.CHEST.defaultBlockState(), facing(Blocks.FURNACE, front), Blocks.BARREL.defaultBlockState()));
                    case OFFICE -> items.addAll(List.of(Blocks.BOOKSHELF.defaultBlockState(), Blocks.LECTERN.defaultBlockState(),
                            Blocks.BOOKSHELF.defaultBlockState(), Blocks.CARTOGRAPHY_TABLE.defaultBlockState(), Blocks.BOOKSHELF.defaultBlockState()));
                    default -> items.addAll(List.of(Blocks.CHEST.defaultBlockState(), Blocks.BOOKSHELF.defaultBlockState(), Blocks.CRAFTING_TABLE.defaultBlockState()));
                }
            }
            // Beds first (they need two spots in a line from the wall), then the rest; a lantern on the first barrel.
            int placedBeds = 0;
            for (int[] sp : spots) {
                if (placedBeds >= beds) break;
                int fx = sp[0] - sp[2], fz = sp[1] - sp[3];
                if (sp[2] == 0 && sp[3] == 0 || !box.contains(fx, fz) || box.edge(fx, fz) || !clear(sp[0], base, sp[1]) || !clear(fx, base, fz)
                        || byDoor(box, fx, base, fz)) continue;
                Direction head = dir(sp[2], sp[3]);
                BlockState bed = Blocks.BED.pick(BEDS[(int) Math.floorMod(b.seed + placedBeds, (long) BEDS.length)]).defaultBlockState()
                        .setValue(net.minecraft.world.level.block.BedBlock.FACING, head);
                put(sp[0], base, sp[1], bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.HEAD), FIT);
                put(fx, base, fz, bed.setValue(net.minecraft.world.level.block.BedBlock.PART, net.minecraft.world.level.block.state.properties.BedPart.FOOT), FIT);
                keepClear.add(key(fx - sp[2], base, fz - sp[3])); // room to get in
                placedBeds++;
            }
            boolean lit = false;
            int i = 0;
            for (int[] sp : spots) {
                if (i >= items.size()) break;
                if (!clear(sp[0], base, sp[1])) continue;
                BlockState item = items.get(i++);
                Direction in = dir(-sp[2], -sp[3]);
                if (item.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) item = item.setValue(BlockStateProperties.HORIZONTAL_FACING, in);
                put(sp[0], base, sp[1], item, FIT);
                if (!lit && (item.is(Blocks.BARREL) || item.is(Blocks.CHEST) || item.is(Blocks.CRAFTING_TABLE))) {
                    put(sp[0], base + 1, sp[1], lantern(false), FIT);
                    lit = true;
                }
            }
            // A table with two chairs in the middle of a home's ground floor, if there's room to walk round it.
            if (home && ground && ix1 - ix0 >= 3 && iz1 - iz0 >= 3) {
                int tx = (ix0 + ix1 + 1) / 2, tz = (iz0 + iz1 + 1) / 2;
                if (clear(tx, base, tz) && clear(tx - 1, base, tz) && clear(tx + 1, base, tz) && !byDoor(box, tx, base, tz)) {
                    put(tx, base, tz, Style.fence(p).defaultBlockState(), FIT);
                    put(tx, base + 1, tz, Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), FIT);
                    put(tx - 1, base, tz, stairs(Style.stairsOf(p), left, false), FIT);
                    put(tx + 1, base, tz, stairs(Style.stairsOf(p), right, false), FIT);
                }
            }
            if (!lit && !spots.isEmpty()) {
                for (int[] sp : spots) if (clear(sp[0], base, sp[1])) { put(sp[0], base, sp[1], Blocks.BARREL.defaultBlockState(), FIT); put(sp[0], base + 1, sp[1], lantern(false), FIT); break; }
            }
        }

        private static final DyeColor[] BEDS = {DyeColor.RED, DyeColor.BLUE, DyeColor.GREEN, DyeColor.YELLOW, DyeColor.WHITE, DyeColor.BROWN};

        Plan finish(String kind) {
            if (furnish) furnishRooms();
            List<Map.Entry<BlockPos, BlockState>> entries = new ArrayList<>(states.entrySet());
            entries.removeIf(e -> e.getValue().isAir());
            entries.sort(java.util.Comparator.comparingInt((Map.Entry<BlockPos, BlockState> e) -> meta.get(e.getKey())[0])
                    .thenComparingInt(e -> meta.get(e.getKey())[1]));
            List<Step> steps = new ArrayList<>(entries.size());
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (Map.Entry<BlockPos, BlockState> e : entries) {
                BlockPos pos = e.getKey();
                steps.add(new Step(pos, e.getValue()));
                minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
            }
            java.util.Set<BlockPos> doorTops = new java.util.HashSet<>();
            for (Step s : steps) if (s.state().getBlock() instanceof DoorBlock) doorTops.add(s.pos().above());
            steps.removeIf(s -> doorTops.contains(s.pos()));
            if (steps.isEmpty()) {
                BlockPos o = at(0, 0, 0);
                return new Plan(List.of(), o, o, households, jobs, kind, List.of(), List.of());
            }
            return new Plan(List.copyOf(steps), new BlockPos(minX, minY, minZ), new BlockPos(maxX, maxY, maxZ), households, jobs, kind,
                    List.copyOf(scaffold), List.copyOf(dig));
        }

        /** Scaffolding poles at the corners of the main mass while it goes up. */
        void scaffoldAround(Box box) {
            scaffold.add(at(box.x0 - 1, 0, box.z0 + 1));
            scaffold.add(at(box.x1 + 1, 0, box.z0 + 1));
            scaffold.add(at(box.x0 - 1, 0, box.z1 - 1));
            scaffold.add(at(box.x1 + 1, 0, box.z1 - 1));
        }
    }

    // ================================================================ style

    /** Blocks for each village style beyond the palette's basics. */
    static final class Style {
        static Block trapdoor(Palette p) {
            return switch (p.key()) {
                case "desert" -> Blocks.JUNGLE_TRAPDOOR;
                case "savanna" -> Blocks.ACACIA_TRAPDOOR;
                case "taiga", "snowy" -> Blocks.SPRUCE_TRAPDOOR;
                case "jungle" -> Blocks.JUNGLE_TRAPDOOR;
                case "swamp" -> Blocks.MANGROVE_TRAPDOOR;
                default -> Blocks.SPRUCE_TRAPDOOR;
            };
        }

        static Block door(Palette p) { return p.door(); }

        static Block fence(Palette p) { return p.fence(); }

        static Block gate(Palette p) {
            return switch (p.key()) {
                case "desert" -> Blocks.JUNGLE_FENCE_GATE;
                case "savanna" -> Blocks.ACACIA_FENCE_GATE;
                case "taiga", "snowy" -> Blocks.SPRUCE_FENCE_GATE;
                case "jungle" -> Blocks.BAMBOO_FENCE_GATE;
                case "swamp" -> Blocks.MANGROVE_FENCE_GATE;
                default -> Blocks.OAK_FENCE_GATE;
            };
        }

        static Block floor(Palette p) { return p.floor(); }

        static Block stairsOf(Palette p) {
            return switch (p.key()) {
                case "desert" -> Blocks.SMOOTH_SANDSTONE_STAIRS;
                case "savanna" -> Blocks.ACACIA_STAIRS;
                case "taiga", "snowy" -> Blocks.SPRUCE_STAIRS;
                case "jungle" -> Blocks.JUNGLE_STAIRS;
                case "swamp" -> Blocks.MANGROVE_STAIRS;
                default -> Blocks.OAK_STAIRS;
            };
        }

        /** Roof stairs and slab; plains and taiga towns mix roof colours from house to house. */
        static Block[] roof(Palette p, long seed) {
            int r = (int) Math.floorMod(seed >>> 11, 3L);
            return switch (p.key()) {
                case "plains" -> r == 0 ? new Block[] {Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB}
                        : r == 1 ? new Block[] {Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB} : new Block[] {Blocks.BRICK_STAIRS, Blocks.BRICK_SLAB};
                case "taiga" -> r == 2 ? new Block[] {Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB} : new Block[] {Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB};
                case "snowy" -> r == 0 ? new Block[] {Blocks.STONE_BRICK_STAIRS, Blocks.STONE_BRICK_SLAB} : new Block[] {Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_SLAB};
                case "savanna" -> r == 0 ? new Block[] {Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB} : new Block[] {Blocks.ACACIA_STAIRS, Blocks.ACACIA_SLAB};
                case "jungle" -> r == 0 ? new Block[] {Blocks.JUNGLE_STAIRS, Blocks.JUNGLE_SLAB} : new Block[] {Blocks.BAMBOO_MOSAIC_STAIRS, Blocks.BAMBOO_MOSAIC_SLAB};
                case "swamp" -> r == 0 ? new Block[] {Blocks.MANGROVE_STAIRS, Blocks.MANGROVE_SLAB} : new Block[] {Blocks.DARK_OAK_STAIRS, Blocks.DARK_OAK_SLAB};
                default -> new Block[] {p.roofStairs(), p.roofSlab()};
            };
        }

        static Block chimney(Palette p) {
            return switch (p.key()) {
                case "desert" -> Blocks.CUT_SANDSTONE;
                case "snowy" -> Blocks.STONE_BRICKS;
                case "jungle" -> Blocks.MOSSY_COBBLESTONE;
                case "swamp" -> Blocks.MUD_BRICKS;
                default -> Blocks.BRICKS;
            };
        }

        static Block wall(Palette p) {
            return switch (p.key()) {
                case "desert" -> Blocks.SANDSTONE_WALL;
                case "jungle" -> Blocks.MOSSY_COBBLESTONE_WALL;
                case "swamp" -> Blocks.MUD_BRICK_WALL;
                default -> Blocks.COBBLESTONE_WALL;
            };
        }

        static Block treeLog(Palette p) {
            return switch (p.key()) {
                case "desert", "savanna" -> Blocks.ACACIA_LOG;
                case "taiga", "snowy" -> Blocks.SPRUCE_LOG;
                case "jungle" -> Blocks.JUNGLE_LOG;
                default -> Blocks.OAK_LOG;
            };
        }

        static Block treeLeaves(Palette p) {
            return switch (p.key()) {
                case "desert", "savanna" -> Blocks.ACACIA_LEAVES;
                case "taiga", "snowy" -> Blocks.SPRUCE_LEAVES;
                case "jungle" -> Blocks.JUNGLE_LEAVES;
                default -> Blocks.OAK_LEAVES;
            };
        }

        static Block hedge(Palette p) {
            return switch (p.key()) {
                case "desert" -> Blocks.ACACIA_LEAVES;
                case "taiga", "snowy" -> Blocks.SPRUCE_LEAVES;
                case "jungle" -> Blocks.JUNGLE_LEAVES;
                default -> Blocks.AZALEA_LEAVES;
            };
        }

        /** Wall infill options: planks of the style, a light plaster, and a darker plank for contrast. */
        static List<BlockState> infills(Palette p) {
            BlockState plaster = Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE).defaultBlockState();
            return switch (p.key()) {
                case "desert" -> List.of(Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(),
                        Blocks.SANDSTONE.defaultBlockState());
                case "savanna" -> List.of(Blocks.ACACIA_PLANKS.defaultBlockState(), Blocks.DYED_TERRACOTTA.pick(DyeColor.ORANGE).defaultBlockState(),
                        plaster);
                case "taiga" -> List.of(Blocks.SPRUCE_PLANKS.defaultBlockState(), plaster, Blocks.SPRUCE_PLANKS.defaultBlockState());
                case "snowy" -> List.of(Blocks.SPRUCE_PLANKS.defaultBlockState(), plaster, Blocks.STONE_BRICKS.defaultBlockState());
                case "jungle" -> List.of(Blocks.JUNGLE_PLANKS.defaultBlockState(), Blocks.BAMBOO_PLANKS.defaultBlockState(), plaster);
                case "swamp" -> List.of(Blocks.MANGROVE_PLANKS.defaultBlockState(), Blocks.PACKED_MUD.defaultBlockState(), plaster);
                default -> List.of(Blocks.OAK_PLANKS.defaultBlockState(), plaster, Blocks.BIRCH_PLANKS.defaultBlockState());
            };
        }

        /** The house wall dressing for a style, infills picked per building. */
        static Wall wall(Canvas c, boolean framed) {
            Palette p = c.p;
            List<BlockState> infill = infills(p);
            BlockState lower = infill.get(c.pick(infill.size())), upper = c.rng.nextFloat() < 0.6f ? infill.get(c.pick(infill.size())) : lower;
            boolean desert = p.key().equals("desert");
            BlockState post = desert ? Blocks.CUT_SANDSTONE.defaultBlockState() : c.log(p.post(), Direction.Axis.Y);
            Block beam = desert ? Blocks.CUT_SANDSTONE : p.post();
            BlockState plinth = desert ? Blocks.SANDSTONE.defaultBlockState() : p.foundation().defaultBlockState();
            return new Wall(post, beam, lower, upper, plinth, trapdoor(p), framed && !desert, true);
        }
    }

    // ================================================================ homes

    static final class Homes {
        /**
         * Low-density homes. A cottage on a single plot; a farmhouse with a wing, a porch and a vegetable plot on a
         * wide plot; a house with a long back garden on a deep plot; a small manor round a garden on a big plot.
         */
        static String house(Canvas c) {
            int wide = c.b.wide, deep = c.b.deep, lv = c.level;
            if (wide >= 2 && deep >= 2) return manor(c);
            if (wide >= 2) return farmhouse(c);
            Wall w = Style.wall(c, true);
            BlockState floor = c.p.floor().defaultBlockState();
            int yard = deep >= 2 ? 1 : (c.D - c.zf >= 8 ? 2 : 1);
            int z0 = c.zf + yard, d = Math.min(deep >= 2 ? 6 : 5, c.D - z0 - 1);
            int width = lv >= 3 || c.pick(2) == 1 ? 7 : 5; // odd: the door and windows sit centred
            int x0 = (c.W - width) / 2 - (lv >= 2 && c.rng.nextBoolean() ? 1 : 0);
            Box main = new Box(x0, z0, x0 + width - 1, z0 + d - 1);
            boolean alongX = c.rng.nextFloat() < 0.6f;
            int door = main.cx();
            c.foundation(main, w.plinth(), floor);
            c.doorway(door, 0, main.z0);
            int storeys = lv >= 2 ? 2 : 1;
            // Level 2: a jettied upper floor over the front.
            Box upper = lv >= 2 && !c.p.flatRoof() ? main.front(1) : main;
            int top = c.walls(main, 0, 1, w, floor);
            if (storeys > 1) top = c.walls(upper, STOREY, 1, w, floor);
            if (storeys > 1 && upper != main) {
                for (int x = main.x0; x <= main.x1; x++) c.put(x, 3, main.z0 - 1, c.beam(c.p.post(), true), FRAME);
                c.brackets(main, 2, 2);
            }
            int ridge = c.roof(upper, top, alongX, w.upper());
            if (lv >= 3 && alongX && !c.p.flatRoof() && upper.w() >= 5) c.dormer(upper, top, upper.cx(), w.upper());
            c.door(door, main.z0, c.back, DoorHingeSide.LEFT);
            if (upper == main) c.doorHood(door, main.z0);
            else c.put(door + 1, 1, main.z0 - 1, c.lantern(true), FIT);
            // A lean-to shed on the side at level 2, a chimney at level 3.
            if (lv >= 2 && main.x1 + 2 < c.W - 1) {
                Box shed = new Box(main.x1 + 1, main.z0 + 1, main.x1 + 2, main.z1);
                shedRoof(c, shed, w);
            }
            if (lv >= 3) c.chimney(main.x0 - 1 >= 0 ? main.x0 - 1 : main.x1 + 1, main.z1 - 1, ridge + 1);
            interior(c, main, door);
            // Garden: a path, flowers either side, a hedge along the road, a tree when there's room.
            c.path(door, c.zf, main.z0 - 1);
            c.hedge(0, door - 2, c.zf);
            c.hedge(door + 2, c.W - 1, c.zf);
            c.flowers(0, c.zf + 1, c.W - 1, main.z0 - 1, 0.35f);
            if (deep >= 2) {
                int gz = main.z1 + 2;
                c.plot(1, gz, 4, Math.min(gz + 3, c.D - 2));
                c.tree(c.W - 2, c.D - 3, 4);
                c.fenceRun(0, main.z1 + 1, c.W - 1, c.D - 1, -1, -1);
            } else if (c.W - main.x1 >= 3 && lv < 2) {
                c.tree(c.W - 2, main.z1 - 1, 4);
            }
            if (lv >= 3) c.lampPost(door + 2 < c.W ? door + 2 : door - 2, c.zf + 1);
            c.scaffoldAround(main);
            c.households = storeys + (lv >= 3 ? 1 : 0) + (deep >= 2 ? 1 : 0);
            return deep >= 2 ? "house" : "cottage";
        }

        /** A farmhouse on a wide plot: a main block with a cross-gabled wing, porch, kitchen garden and woodpile. */
        static String farmhouse(Canvas c) {
            int lv = c.level;
            Wall w = Style.wall(c, true);
            BlockState floor = c.p.floor().defaultBlockState();
            int z0 = c.zf + 2;
            int depth = Math.min(5, c.D - z0 - 1);
            boolean wingLeft = c.rng.nextBoolean();
            int mainW = 7, wingW = 4 + (lv >= 2 ? 1 : 0);
            int mx0 = wingLeft ? 1 + wingW : 1;
            Box main = new Box(mx0, z0, mx0 + mainW - 1, z0 + depth - 1);
            Box wing = wingLeft ? new Box(1, z0 - 1, wingW, z0 + depth - 1) : new Box(main.x1 + 1, z0 - 1, main.x1 + wingW, z0 + depth - 1);
            c.foundation(main, w.plinth(), floor);
            c.foundation(wing, w.plinth(), floor);
            int door = main.cx();
            c.doorway(door, 0, main.z0);
            int wingDoor = wing.cx();
            c.doorway(wingDoor, 0, wing.z0);
            int storeys = lv >= 2 ? 2 : 1;
            int topWing = c.walls(wing, 0, 1, w.with(w.upper(), w.upper()), floor);
            int top = c.walls(main, 0, storeys, w, floor);
            int ridge = c.roof(main, top, true, w.upper());
            c.roof(wing, topWing, false, w.upper());
            if (storeys == 1 && lv >= 1 && !c.p.flatRoof()) c.dormer(main, top, main.cx() + (wingLeft ? 1 : -1), w.upper());
            c.door(door, main.z0, c.back, DoorHingeSide.LEFT);
            c.door(wingDoor, wing.z0, c.back, DoorHingeSide.RIGHT);
            if (lv >= 2) c.porch(new Box(main.x0, main.z0, main.x1, main.z1), 2);
            else c.doorHood(door, main.z0);
            c.chimney(wingLeft ? main.x1 + 1 : main.x0 - 1, main.z1 - 1, ridge + 1);
            interior(c, main, door);
            // Yard: kitchen garden behind the wing side, a woodpile and barrels, a tree, fence and gate.
            int gx0 = wingLeft ? main.x1 + 2 : 1, gx1 = wingLeft ? c.W - 2 : main.x0 - 2;
            if (gx1 - gx0 >= 2) {
                c.plot(gx0, z0, gx1, Math.min(z0 + 3, c.D - 2));
                c.woodpile(gx0, Math.min(gx0 + 1, gx1), c.D - 2, 2);
            }
            c.tree(wingLeft ? c.W - 2 : 1, c.zf + 1, 5);
            c.path(door, c.zf, main.z0 - (lv >= 2 ? 3 : 1));
            c.path(wingDoor, c.zf, wing.z0 - 1);
            c.fenceRun(0, c.zf, c.W - 1, c.D - 1, door, c.zf);
            c.flowers(1, c.zf + 1, c.W - 2, z0 - 2, 0.3f);
            c.put(wingLeft ? main.x1 + 1 : main.x0 - 1, 0, main.z0 - 1, Blocks.BARREL.defaultBlockState(), GARDEN);
            if (lv >= 3) {
                c.lampPost(door - 2, c.zf + 1);
                c.lampPost(door + 2, c.zf + 1);
            }
            c.scaffoldAround(main);
            c.households = 2 + storeys + (lv >= 3 ? 1 : 0);
            return "farmhouse";
        }

        /** A small manor on a big plot: an L of two wings round a walled garden with a tree and a fountain. */
        static String manor(Canvas c) {
            int lv = c.level;
            Wall w = Style.wall(c, true);
            BlockState floor = c.p.floor().defaultBlockState();
            int z0 = c.zf + 3;
            Box main = new Box(2, z0, 12, z0 + 5);
            Box wing = new Box(8, main.z1 + 1, 12, Math.min(c.D - 2, main.z1 + 7));
            c.foundation(main, w.plinth(), floor);
            c.foundation(wing, w.plinth(), floor);
            int door = main.cx();
            c.doorway(door, 0, main.z0);
            int storeys = lv >= 2 ? 2 : 1;
            int top = c.walls(main, 0, storeys, w, floor);
            int topWing = c.walls(wing, 0, Math.max(1, storeys - 1), w, floor);
            int ridge = c.roof(main, top, true, w.upper());
            c.roof(wing, topWing, false, w.upper());
            if (!c.p.flatRoof()) {
                c.dormer(main, top, main.x0 + 2, w.upper());
                if (lv >= 3) c.dormer(main, top, main.x1 - 2, w.upper());
            }
            c.door(door, main.z0, c.back, DoorHingeSide.LEFT);
            // A projecting centre bay with its own gable facing the road, over the front door.
            Box bay = new Box(door - 2, main.z0 - 2, door + 2, main.z0);
            c.foundation(bay, w.plinth(), floor);
            for (int z = bay.z0; z < main.z0; z++) for (int x = bay.x0; x <= bay.x1; x++) c.erase(x, 0, z);
            c.doorway(door, 0, bay.z0);
            int bayTop = c.walls(bay, 0, storeys, w, floor);
            c.erase(door, 0, main.z0);
            c.erase(door, 1, main.z0);
            if (!c.p.flatRoof()) c.gable(bay, bayTop, false, Style.roof(c.p, c.b.seed)[0], Style.roof(c.p, c.b.seed)[1], w.upper());
            c.door(door, bay.z0, c.back, DoorHingeSide.LEFT);
            c.doorHood(door, bay.z0);
            c.chimney(main.x0 - 1, main.z0 + 2, ridge + 1);
            if (lv >= 3) c.chimney(main.x1 + 1, main.z0 + 2, ridge + 1);
            interior(c, main, door);
            // The garden behind: a fountain, a tree, flower beds and benches, inside a hedge.
            int gx0 = 2, gx1 = wing.x0 - 2, gz0 = main.z1 + 2, gz1 = c.D - 3;
            if (gx1 - gx0 >= 2 && gz1 - gz0 >= 2) {
                fountain(c, (gx0 + gx1) / 2, (gz0 + gz1) / 2);
                c.tree(gx0, gz1, 5);
                c.flowers(gx0, gz0, gx1, gz1, 0.25f);
                c.bench(gx1 - 1, gx1, gz0, c.back);
            }
            c.path(door, c.zf, main.z0 - 3);
            c.hedge(0, door - 2, c.zf);
            c.hedge(door + 2, c.W - 1, c.zf);
            c.flowers(1, c.zf + 1, c.W - 2, z0 - 3, 0.3f);
            c.lampPost(door - 2, c.zf + 1);
            c.lampPost(door + 2, c.zf + 1);
            c.fenceRun(0, main.z1 + 1, c.W - 1, c.D - 1, -1, -1);
            c.scaffoldAround(main);
            c.households = 3 + storeys * 2 + (lv >= 3 ? 1 : 0);
            return "manor";
        }

        /**
         * High-density homes, spread out rather than stacked: a terrace of narrow houses side by side, each with its
         * own front door, wall colour and roof height; a courtyard block round a shared garden on a big plot.
         */
        static String terrace(Canvas c) {
            int lv = c.level;
            if (c.b.wide >= 2 && c.b.deep >= 2) return courtyard(c);
            BlockState floor = c.p.floor().defaultBlockState();
            int units = c.b.wide >= 2 ? 3 : 1;
            int unitW = c.b.wide >= 2 ? 5 : 7; // odd, so each front door is centred
            int x = (c.W - units * unitW) / 2;
            int z0 = c.zf + 1;
            int depth = Math.min(c.b.deep >= 2 ? 8 : 6, c.D - z0 - 1);
            int storeysBase = lv >= 3 ? 3 : 2;
            int ridgeMax = 0;
            for (int u = 0; u < units; u++) {
                Wall w = Style.wall(c, false);
                Box unit = new Box(x + u * unitW, z0 + (u == 1 ? 0 : c.pick(2)), x + (u + 1) * unitW - 1, z0 + depth - 1);
                int storeys = Math.max(1, storeysBase - (u != 1 && c.rng.nextFloat() < 0.4f ? 1 : 0));
                c.foundation(unit, w.plinth(), floor);
                int door = unit.cx();
                c.doorway(door, 0, unit.z0);
                int top = c.walls(unit, 0, storeys, w, floor);
                int ridge = c.roof(unit, top, false, w.upper());
                ridgeMax = Math.max(ridgeMax, ridge);
                c.door(door, unit.z0, c.back, u % 2 == 0 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT);
                c.doorHood(door, unit.z0);
                c.path(door, c.zf, unit.z0 - 1);
                // Window boxes of flowers under the upper windows, a balcony on the middle house.
                if (u == 1 && storeys >= 2) balcony(c, unit, STOREY);
                if (lv >= 2 && u == units - 1) c.chimney(unit.x1 + 1 < c.W ? unit.x1 + 1 : unit.x1, unit.z1 - 1, ridge + 1);
                c.households += storeys * (unitW >= 6 ? 2 : 1);
                c.flowers(unit.x0, c.zf + 1, unit.x1, unit.z0 - 1, 0.25f);
            }
            if (c.b.deep >= 2) {
                int gz = z0 + depth + 1;
                c.tree(2, Math.min(gz + 2, c.D - 2), 4);
                c.flowers(4, gz, c.W - 2, c.D - 2, 0.15f);
                c.fenceRun(0, gz - 1, c.W - 1, c.D - 1, -1, -1);
            }
            c.lampPost(0, c.zf + 1);
            c.scaffoldAround(new Box(x, z0, x + units * unitW - 1, z0 + depth - 1));
            return units > 1 ? "terrace" : "townhouse";
        }

        /** A courtyard block: three ranges of two- or three-storey homes round a shared garden, a gateway at the front. */
        static String courtyard(Canvas c) {
            int lv = c.level;
            BlockState floor = c.p.floor().defaultBlockState();
            Wall w = Style.wall(c, true);
            int z0 = c.zf + 1, storeys = lv >= 3 ? 3 : 2;
            Box frontL = new Box(1, z0, 6, z0 + 5), frontR = new Box(9, z0, 14, z0 + 5);
            Box backR = new Box(1, c.D - 7, 14, c.D - 2);
            for (Box bx : new Box[] {frontL, frontR, backR}) c.foundation(bx, w.plinth(), floor);
            int dl = frontL.cx(), dr = frontR.cx();
            c.doorway(dl, 0, frontL.z0);
            c.doorway(dr, 0, frontR.z0);
            c.doorway(backR.cx(), 0, backR.z0);
            int t1 = c.walls(frontL, 0, storeys, w, floor);
            int t2 = c.walls(frontR, 0, storeys, w, floor);
            int t3 = c.walls(backR, 0, storeys, w.with(w.upper(), w.lower()), floor);
            c.roof(frontL, t1, false, w.upper());
            c.roof(frontR, t2, false, w.upper());
            int ridge = c.roof(backR, t3, true, w.upper());
            if (!c.p.flatRoof()) {
                c.dormer(backR, t3, backR.x0 + 3, w.upper());
                c.dormer(backR, t3, backR.x1 - 3, w.upper());
            }
            c.door(dl, frontL.z0, c.back, DoorHingeSide.LEFT);
            c.door(dr, frontR.z0, c.back, DoorHingeSide.RIGHT);
            c.door(backR.cx(), backR.z0, c.back, DoorHingeSide.LEFT);
            c.doorHood(dl, frontL.z0);
            c.doorHood(dr, frontR.z0);
            // Gateway between the front ranges: an arch with a lantern, and the garden through it.
            Block[] r = Style.roof(c.p, c.b.seed);
            for (int y = 0; y <= 2; y++) { c.put(7, y, z0, w.post(), FRAME); c.put(8, y, z0, w.post(), FRAME); }
            c.erase(7, 0, z0); c.erase(8, 0, z0); c.erase(7, 1, z0); c.erase(8, 1, z0);
            c.put(7, 3, z0, c.slab(r[1], SlabType.BOTTOM), ROOF);
            c.put(8, 3, z0, c.slab(r[1], SlabType.BOTTOM), ROOF);
            c.put(7, 2, z0 - 1, c.lantern(false), FIT);
            int gz0 = frontL.z1 + 1, gz1 = backR.z0 - 1;
            for (int x = 7; x <= 8; x++) c.path(x, c.zf, gz1);
            if (gz1 - gz0 >= 1) {
                c.tree(3, gz0 + (gz1 - gz0) / 2, 4);
                c.tree(12, gz0 + (gz1 - gz0) / 2, 4);
                c.flowers(1, gz0, 14, gz1, 0.2f);
                c.bench(5, 6, gz1, c.front);
                c.bench(9, 10, gz1, c.front);
            }
            c.chimney(backR.x0 - 1 >= 0 ? backR.x0 - 1 : 0, backR.z0 + 2, ridge + 1);
            c.lampPost(0, c.zf + 1);
            c.lampPost(c.W - 1, c.zf + 1);
            c.scaffoldAround(backR);
            c.households = storeys * 6;
            return "courtyard";
        }

        /** A shed with a lean-to slab roof against a wall. */
        static void shedRoof(Canvas c, Box shed, Wall w) {
            BlockState plank = w.upper();
            for (int x = shed.x0; x <= shed.x1; x++) for (int z = shed.z0; z <= shed.z1; z++) {
                c.put(x, -1, z, c.p.foundation().defaultBlockState(), GROUND);
                if (shed.edge(x, z)) for (int y = 0; y <= 1; y++) c.put(x, y, z, z == shed.z0 && y == 0 && x == shed.x0 ? Blocks.AIR.defaultBlockState()
                        : shed.corner(x, z) ? w.post() : plank, FRAME);
                c.put(x, 2, z, c.slab(Style.roof(c.p, c.b.seed)[1], SlabType.BOTTOM), ROOF);
            }
            c.put(shed.x1, 0, shed.z0 - 1, Blocks.BARREL.defaultBlockState(), GARDEN);
        }

        /** A small balcony on an upper floor: a door out, a slab floor, a fence rail and a flower pot. */
        static void balcony(Canvas c, Box unit, int y) {
            int x0 = unit.x0 + 1, x1 = unit.x1 - 1, z = unit.z0 - 1;
            for (int x = x0; x <= x1; x++) {
                c.put(x, y - 1, z, c.slab(Style.roof(c.p, c.b.seed)[1], SlabType.TOP), FRAME);
                c.put(x, y, z, Style.fence(c.p), FIT);
            }
            c.put(unit.cx(), y + 1, unit.z0, Blocks.GLASS_PANE, FRAME);
            c.put(x0, y + 1, z, Blocks.POTTED_RED_TULIP, FIT);
        }

        /** A few things inside so a lit window shows a home: a table, a barrel, a lantern, a bed of wool. */
        static void interior(Canvas c, Box box, int door) {
            if (true) return; // the furnishing pass at finish now fills every room
            if (box.w() < 4 || box.d() < 4) return;
            c.put(box.x0 + 1, 0, box.z1 - 1, Blocks.CRAFTING_TABLE, FIT);
            c.put(box.x1 - 1, 0, box.z1 - 1, Blocks.BARREL.defaultBlockState(), FIT);
            c.put(box.x1 - 1, 0, box.z0 + 1, c.lantern(false), FIT);
            c.put(box.x0 + 1, 0, box.z0 + 1, Blocks.POTTED_POPPY, FIT);
        }

        /** A stone basin with water and a little spout. */
        static void fountain(Canvas c, int x, int z) {
            BlockState rim = Blocks.STONE_BRICK_WALL.defaultBlockState(), stone = Blocks.STONE_BRICKS.defaultBlockState();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                c.put(x + dx, -1, z + dz, stone, GROUND);
                if (dx != 0 || dz != 0) c.put(x + dx, 0, z + dz, rim, FRAME);
            }
            c.put(x, 0, z, Blocks.WATER, FIT);
            c.put(x, -1, z, stone, GROUND);
        }
    }

    // ================================================================ shops, markets, workshops, offices

    static final class Trade {
        private static final Block[] STATIONS = {Blocks.SMITHING_TABLE, Blocks.FLETCHING_TABLE, Blocks.CARTOGRAPHY_TABLE, Blocks.LOOM,
                Blocks.LECTERN, Blocks.BREWING_STAND, Blocks.GRINDSTONE, Blocks.BARREL};
        private static final DyeColor[] AWNINGS = {DyeColor.RED, DyeColor.BLUE, DyeColor.GREEN, DyeColor.YELLOW, DyeColor.ORANGE, DyeColor.PURPLE};

        /** A striped awning over a run of the front wall: wool stairs sloping down to the street. */
        static void awning(Canvas c, int x0, int x1, int z, int y, long seed) {
            DyeColor a = AWNINGS[(int) Math.floorMod(seed >>> 5, (long) AWNINGS.length)];
            DyeColor b = a == DyeColor.YELLOW ? DyeColor.ORANGE : DyeColor.WHITE;
            for (int x = x0; x <= x1; x++) {
                c.put(x, y, z - 1, c.stairs(Blocks.WOOL_STAIRS.pick(x % 2 == 0 ? a : b), c.back, false), FIT);
            }
        }

        /** Shop windows across the ground floor front, either side of the door. */
        static void shopFront(Canvas c, Box box, int door) {
            for (int x = box.x0 + 1; x < box.x1; x++) {
                if (x == door) continue;
                c.put(x, 0, box.z0, Blocks.GLASS_PANE, FRAME);
                c.put(x, 1, box.z0, Blocks.GLASS_PANE, FRAME);
            }
        }

        /**
         * Low-density shops: a shophouse with a glazed front, striped awning and a flat above (from level 2); an inn
         * with a cafe terrace on a wide plot; a shop with a yard of crates on a deep plot.
         */
        static String shop(Canvas c) {
            int lv = c.level;
            if (c.b.wide >= 2) return inn(c);
            Wall w = Style.wall(c, true);
            BlockState floor = c.p.floor().defaultBlockState();
            int z0 = c.zf + 2, depth = Math.min(c.b.deep >= 2 ? 7 : 5, c.D - z0 - 1);
            Box box = new Box(1, z0, 6, z0 + depth - 1);
            int door = 2 + c.pick(3);
            c.foundation(box, w.plinth(), floor);
            c.doorway(door, 0, box.z0);
            int storeys = lv >= 2 ? 2 : 1;
            int top = c.walls(box, 0, storeys, w, floor);
            shopFront(c, box, door);
            boolean alongX = storeys == 1 ? false : c.rng.nextBoolean();
            int ridge = c.roof(box, top, alongX, w.upper());
            if (lv >= 3 && alongX) c.dormer(box, top, box.cx(), w.upper());
            c.door(door, box.z0, c.back, DoorHingeSide.LEFT);
            awning(c, box.x0, box.x1, box.z0, 2, c.b.seed);
            DyeColor colour = AWNINGS[(int) Math.floorMod(c.b.seed >>> 5, (long) AWNINGS.length)];
            if (!alongX && !c.p.flatRoof()) c.banner(box.cx(), top + 1, box.z0 - 1, colour, c.front);
            else c.banner(box.x0 - 1 >= 0 ? box.x0 - 1 : box.x0, 2, box.z0 + 1, colour, c.left);
            Block station = STATIONS[c.pick(STATIONS.length)];
            c.put(box.x0 + 1, 0, box.z1 - 1, c.facing(station, c.front), FIT);
            c.put(box.x1 - 1, 0, box.z1 - 1, Blocks.BARREL.defaultBlockState(), FIT);
            c.put(box.x1 - 1, 0, box.z0 + 1, c.lantern(false), FIT);
            if (lv >= 2) c.chimney(box.x1 + 1, box.z1 - 1, ridge + 1);
            // Goods out front: crates, a flower pot, a bench.
            c.put(box.x0, 0, box.z0 - 2, Blocks.BARREL.defaultBlockState(), GARDEN);
            c.put(box.x1, 0, box.z0 - 2, Blocks.BARREL.defaultBlockState(), GARDEN);
            c.put(box.x1, 1, box.z0 - 2, Blocks.POTTED_RED_TULIP, GARDEN);
            if (lv >= 3) c.bench(box.x0 + 1, box.x0 + 2, box.z0 - 2, c.front);
            c.path(door, c.zf, box.z0 - 1);
            c.path(door - 1, c.zf, box.z0 - 1);
            if (c.b.deep >= 2) {
                int yz = box.z1 + 2;
                c.fenceRun(0, box.z1 + 1, c.W - 1, c.D - 1, -1, -1);
                for (int x = 1; x <= 3; x++) c.put(x, 0, c.D - 2, Blocks.BARREL.defaultBlockState(), GARDEN);
                c.put(1, 1, c.D - 2, Blocks.BARREL.defaultBlockState(), GARDEN);
                c.woodpile(5, 6, c.D - 2, 2);
                c.tree(6, yz, 4);
            }
            c.lampPost(c.W - 1, c.zf + 1);
            c.scaffoldAround(box);
            c.jobs = 2 + storeys + (lv >= 3 ? 2 : 0) + (c.b.deep >= 2 ? 1 : 0);
            return "shop";
        }

        /** An inn with a cafe terrace: tables and chairs under an awning, string of lanterns, flower pots. */
        static String inn(Canvas c) {
            int lv = c.level;
            Wall w = Style.wall(c, true);
            BlockState floor = c.p.floor().defaultBlockState();
            int z0 = c.zf + 2, depth = Math.min(c.b.deep >= 2 ? 8 : 6, c.D - z0 - 1);
            boolean terraceLeft = c.rng.nextBoolean();
            int bx0 = terraceLeft ? 6 : 1;
            Box box = new Box(bx0, z0, bx0 + 8, z0 + depth - 1);
            int door = box.cx();
            c.foundation(box, w.plinth(), floor);
            c.doorway(door, 0, box.z0);
            int storeys = lv >= 2 ? 2 : 1;
            Box upper = storeys > 1 && !c.p.flatRoof() ? box.front(1) : box;
            int top = c.walls(box, 0, 1, w, floor);
            if (storeys > 1) {
                top = c.walls(upper, STOREY, 1, w, floor);
                if (upper != box) {
                    for (int x = box.x0; x <= box.x1; x++) c.put(x, 3, box.z0 - 1, c.beam(c.p.post(), true), FRAME);
                    c.brackets(box, 2, 2);
                }
            }
            shopFront(c, box, door);
            int ridge = c.roof(upper, top, true, w.upper());
            if (!c.p.flatRoof()) {
                c.dormer(upper, top, upper.x0 + 2, w.upper());
                if (lv >= 3) c.dormer(upper, top, upper.x1 - 2, w.upper());
            }
            c.door(door, box.z0, c.back, DoorHingeSide.LEFT);
            if (upper == box) awning(c, box.x0, box.x1, box.z0, 2, c.b.seed);
            c.banner(door + 2, upper == box ? top + 1 : 2, upper == box ? box.z0 - 1 : box.z0 - 2, DyeColor.RED, c.front);
            c.chimney(terraceLeft ? box.x1 + 1 : box.x0 - 1, box.z1 - 1, ridge + 1);
            c.put(box.x0 + 1, 0, box.z1 - 1, Blocks.BARREL.defaultBlockState(), FIT);
            c.put(box.x0 + 2, 0, box.z1 - 1, Blocks.BARREL.defaultBlockState(), FIT);
            c.put(box.x1 - 1, 0, box.z1 - 1, Blocks.SMOKER.defaultBlockState(), FIT);
            // The terrace: a deck, tables (fence + pressure plate), chairs, a canopy on posts.
            int tx0 = terraceLeft ? 1 : box.x1 + 2, tx1 = terraceLeft ? box.x0 - 2 : c.W - 2;
            int tz0 = z0 - 1, tz1 = Math.min(box.z1, tz0 + 5);
            Block slabB = Style.roof(c.p, c.b.seed)[1];
            for (int x = tx0; x <= tx1; x++) for (int z = tz0; z <= tz1; z++) c.put(x, -1, z, Style.floor(c.p), GROUND);
            for (int z = tz0 + 1; z <= tz1 - 1; z += 3) {
                int tx = (tx0 + tx1) / 2;
                c.put(tx, 0, z, Style.fence(c.p), GARDEN);
                c.put(tx, 1, z, Blocks.SPRUCE_PRESSURE_PLATE, GARDEN);
                c.put(tx - 1, 0, z, c.stairs(Style.stairsOf(c.p), c.left, false), GARDEN);
                c.put(tx + 1, 0, z, c.stairs(Style.stairsOf(c.p), c.right, false), GARDEN);
            }
            if (lv >= 2) {
                for (int x = tx0; x <= tx1; x++) for (int z = tz0; z <= tz1; z++) {
                    boolean post = (x == tx0 || x == tx1) && (z == tz0 || z == tz1);
                    if (post) { c.put(x, 0, z, Style.fence(c.p), FRAME); c.put(x, 1, z, Style.fence(c.p), FRAME); }
                    c.put(x, 2, z, c.slab(Blocks.WOOL_SLAB.pick((x + z) % 2 == 0 ? DyeColor.WHITE : DyeColor.RED), SlabType.BOTTOM), ROOF);
                }
                c.put(tx0, 1, tz0 + 1, c.lantern(true), FIT);
                c.put(tx1, 1, tz1 - 1, c.lantern(true), FIT);
            } else {
                c.lampPost(tx0, tz0);
            }
            c.flowers(tx0, c.zf + 1, tx1, tz0 - 1, 0.3f);
            c.path(door, c.zf, box.z0 - (upper == box ? 1 : 2));
            c.hedge(0, c.W - 1, c.zf);
            c.erase(door, 0, c.zf);
            c.scaffoldAround(box);
            c.jobs = 4 + storeys * 2 + (lv >= 3 ? 2 : 0);
            return "inn";
        }

        /**
         * High-density shops: a market hall with an open arcade of stalls and a hall above on a wide plot; a market
         * square with stalls, a fountain and a covered hall on a big plot.
         */
        static String market(Canvas c) {
            int lv = c.level;
            if (c.b.wide < 2) return shop(c);
            boolean square = c.b.deep >= 2;
            int z0 = square ? c.D - 8 : c.zf + 1;
            Box hall = new Box(1, z0, c.W - 2, Math.min(c.D - 2, z0 + 6));
            Wall w = Style.wall(c, true);
            BlockState floor = Blocks.STONE_BRICKS.defaultBlockState();
            c.foundation(hall, Blocks.STONE_BRICKS.defaultBlockState(), floor);
            // Ground floor: stone piers with arches between, open to the street, stalls under the arches.
            BlockState pier = c.p.key().equals("desert") ? Blocks.CUT_SANDSTONE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
            Block arch = c.p.key().equals("desert") ? Blocks.SMOOTH_SANDSTONE_STAIRS : Blocks.STONE_BRICK_STAIRS;
            for (int x = hall.x0; x <= hall.x1; x++) for (int z = hall.z0; z <= hall.z1; z++) {
                if (!hall.edge(x, z)) continue;
                boolean alongX = z == hall.z0 || z == hall.z1;
                int i = alongX ? x - hall.x0 : z - hall.z0;
                boolean isPier = hall.corner(x, z) || i % 3 == 0;
                if (isPier) for (int y = 0; y <= 2; y++) c.put(x, y, z, pier, FRAME);
                else {
                    Direction toPier = alongX ? ((i % 3 == 1) ? c.left : c.right) : ((i % 3 == 1) ? c.front : c.back);
                    c.put(x, 2, z, c.stairs(arch, toPier, true), FRAME);
                }
                c.put(x, 3, z, hall.corner(x, z) ? pier : c.beam(c.p.post(), alongX), FRAME);
            }
            int storeys = lv >= 2 ? 2 : 1;
            for (int x = hall.x0 + 1; x < hall.x1; x++) for (int z = hall.z0 + 1; z < hall.z1; z++) c.put(x, 3, z, c.p.floor().defaultBlockState(), FRAME);
            int top = 3;
            if (storeys > 1) top = c.walls(hall, STOREY, 1, w, c.p.floor().defaultBlockState());
            else c.masses.add(new int[] {hall.x0, hall.z0, hall.x1, hall.z1, 3});
            Block[] r = Style.roof(c.p, c.b.seed);
            int ridge = c.p.flatRoof() ? c.flat(hall, top, c.p.stone().defaultBlockState(), Style.wall(c.p).defaultBlockState(), c.p.trim().defaultBlockState())
                    : c.hip(hall, top, r[0], r[1]);
            // Stalls inside: a workstation and a coloured cloth over each bay.
            for (int x = hall.x0 + 1; x < hall.x1 - 1; x += 3) {
                c.put(x, 0, hall.z0 + 1, c.facing(STATIONS[c.pick(STATIONS.length)], c.front), FIT);
                c.put(x + 1, 0, hall.z0 + 1, Blocks.BARREL.defaultBlockState(), FIT);
                c.put(x, 0, hall.z1 - 1, c.facing(STATIONS[c.pick(STATIONS.length)], c.back), FIT);
                c.put(x + 1, 1, hall.z1 - 1, c.lantern(false), FIT);
                c.put(x + 1, 0, hall.z1 - 1, Blocks.BARREL.defaultBlockState(), FIT);
            }
            if (lv >= 3 && !c.p.flatRoof()) cupola(c, hall.cx(), hall.cz(), ridge, true);
            if (square) {
                // The square in front: paving, stalls with awnings round a fountain, trees and lamps.
                for (int x = 0; x < c.W; x++) for (int z = c.zf; z < hall.z0; z++) {
                    c.put(x, -1, z, ((x + z) % 3 == 0 ? Blocks.COBBLESTONE : Blocks.STONE_BRICKS).defaultBlockState(), GROUND);
                }
                Homes.fountain(c, c.W / 2, (c.zf + hall.z0) / 2);
                int[][] stalls = {{2, c.zf + 2}, {c.W - 5, c.zf + 2}};
                for (int[] st : stalls) stall(c, st[0], st[1]);
                c.tree(1, hall.z0 - 2, 4);
                c.tree(c.W - 2, hall.z0 - 2, 4);
                c.lampPost(c.W / 2 - 3, c.zf);
                c.lampPost(c.W / 2 + 3, c.zf);
                c.bench(c.W / 2 - 1, c.W / 2 + 1, hall.z0 - 2, c.front);
            } else {
                c.lampPost(0, c.zf);
                c.lampPost(c.W - 1, c.zf);
                c.flowers(1, c.zf, c.W - 2, hall.z0 - 1, 0.15f);
            }
            c.scaffoldAround(hall);
            c.jobs = 8 + (storeys - 1) * 6 + (square ? 6 : 0) + (lv >= 3 ? 4 : 0);
            return square ? "market_square" : "market";
        }

        /** A market stall: a counter of barrels and a workstation under a striped cloth on four posts. */
        static void stall(Canvas c, int x0, int z0) {
            DyeColor a = AWNINGS[c.pick(AWNINGS.length)];
            for (int x = x0; x <= x0 + 2; x++) for (int z = z0; z <= z0 + 1; z++) {
                if ((x == x0 || x == x0 + 2)) { c.put(x, 0, z, Style.fence(c.p), FRAME); c.put(x, 1, z, Style.fence(c.p), FRAME); }
                c.put(x, 2, z, c.slab(Blocks.WOOL_SLAB.pick(x % 2 == 0 ? a : DyeColor.WHITE), SlabType.BOTTOM), ROOF);
            }
            c.put(x0 + 1, 0, z0, Blocks.BARREL.defaultBlockState(), FIT);
            c.put(x0 + 1, 0, z0 + 1, c.facing(STATIONS[c.pick(STATIONS.length)], c.front), FIT);
        }

        /** A bell cupola on a ridge: four posts, an open lantern room, a little hipped cap. */
        static void cupola(Canvas c, int x, int z, int ridge, boolean bell) {
            Block[] r = Style.roof(c.p, c.b.seed);
            BlockState post = c.log(c.p.post(), Direction.Axis.Y);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                c.put(x + dx, ridge, z + dz, c.p.floor().defaultBlockState(), ROOF);
                if (dx != 0 && dz != 0) for (int y = ridge + 1; y <= ridge + 2; y++) c.put(x + dx, y, z + dz, post, ROOF);
            }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.put(x + dx, ridge + 3, z + dz, c.p.floor().defaultBlockState(), ROOF);
            c.hip(new Box(x - 1, z - 1, x + 1, z + 1), ridge + 4, r[0], r[1]);
            if (bell) c.put(x, ridge + 2, z, Blocks.BELL.defaultBlockState().setValue(BellBlock.ATTACHMENT, BellAttachType.CEILING)
                    .setValue(BellBlock.FACING, c.front), FIT);
            else c.put(x, ridge + 2, z, c.lantern(true), FIT);
        }

        /**
         * Industry: a workshop with a stone ground floor and timber loft, wide doors, a forge with a tall brick
         * chimney, and a fenced yard of timber, hay and barrels; a warehouse too on a big plot.
         */
        static String workshop(Canvas c) {
            int lv = c.level;
            Wall w = Style.wall(c, true);
            Wall stone = new Wall(c.p.key().equals("desert") ? Blocks.CUT_SANDSTONE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState(),
                    w.beam(), c.p.foundation().defaultBlockState(), w.upper(), null, null, false, false);
            BlockState floor = Blocks.COBBLESTONE.defaultBlockState();
            int z0 = c.zf + 2;
            int width = c.b.wide >= 2 ? 9 : 6, depth = Math.min(c.b.deep >= 2 ? 7 : 6, c.D - z0 - 1);
            Box shop = new Box(1, z0, width, z0 + depth - 1);
            c.foundation(shop, c.p.foundation().defaultBlockState(), floor);
            int door = shop.x0 + 2;
            c.doorway(door, 0, shop.z0);
            c.doorway(door + 1, 0, shop.z0);
            int storeys = lv >= 2 ? 2 : 1;
            int top = c.walls(shop, 0, 1, stone, c.p.floor().defaultBlockState());
            if (storeys > 1) top = c.walls(shop, STOREY, 1, w, c.p.floor().defaultBlockState());
            int ridge = c.roof(shop, top, c.b.wide < 2, w.upper());
            c.door(door, shop.z0, c.back, DoorHingeSide.LEFT);
            c.door(door + 1, shop.z0, c.back, DoorHingeSide.RIGHT);
            // Forge in the back corner: blast furnace, anvil, and a brick chimney well above the roof.
            c.put(shop.x1 - 1, 0, shop.z1 - 1, c.facing(Blocks.BLAST_FURNACE, c.front), FIT);
            c.put(shop.x1 - 2, 0, shop.z1 - 1, c.facing(Blocks.ANVIL, c.right), FIT);
            c.put(shop.x0 + 1, 0, shop.z1 - 1, Blocks.STONECUTTER.defaultBlockState(), FIT);
            c.put(shop.x0 + 1, 0, shop.z0 + 1, Blocks.GRINDSTONE.defaultBlockState(), FIT);
            c.chimney(shop.x1 + 1, shop.z1 - 1, ridge + 2);
            if (lv >= 3) c.chimney(shop.x1 + 1, shop.z0 + 1, ridge + 1);
            c.put(door - 1, 2, shop.z0 - 1, c.lantern(false), FIT);
            // Its trade shows by the door: the banner's colour, and a stack of what it makes.
            var trade = com.cloudkingtv.townstead.industry.Industry.of(c.b);
            DyeColor colour = trade == null ? DyeColor.BLACK : switch (trade) {
                case CARPENTER -> DyeColor.BROWN;
                case MASON -> DyeColor.GRAY;
                case GLASSWORKS -> DyeColor.LIGHT_BLUE;
                case BRICKWORKS -> DyeColor.RED;
                case COOPER -> DyeColor.ORANGE;
                default -> DyeColor.BLACK;
            };
            c.banner(door + 3 <= shop.x1 ? door + 3 : shop.x1, 2, shop.z0 - 1, colour, c.front);
            if (trade != null && shop.x1 - 1 > door + 1) {
                BlockState goods = switch (trade) {
                    case CARPENTER -> c.p.floor().defaultBlockState();
                    case MASON -> Blocks.STONE_BRICKS.defaultBlockState();
                    case GLASSWORKS -> Blocks.GLASS.defaultBlockState();
                    case BRICKWORKS -> Blocks.BRICKS.defaultBlockState();
                    case COOPER -> Blocks.BARREL.defaultBlockState();
                    default -> null;
                };
                if (goods != null) {
                    c.put(shop.x1 - 1, 0, shop.z0 - 1, goods, GARDEN);
                    c.put(shop.x1, 0, shop.z0 - 1, goods, GARDEN);
                    c.put(shop.x1, 1, shop.z0 - 1, goods, GARDEN);
                }
                // The glassworks and brickworks fire kilns instead of a forge.
                if (trade == com.cloudkingtv.townstead.industry.Industry.Trade.GLASSWORKS || trade == com.cloudkingtv.townstead.industry.Industry.Trade.BRICKWORKS) {
                    c.put(shop.x1 - 1, 0, shop.z1 - 1, c.facing(Blocks.FURNACE, c.front), FIT);
                    c.put(shop.x1 - 2, 0, shop.z1 - 1, c.facing(Blocks.FURNACE, c.front), FIT);
                }
            }
            // The yard: fence, log stacks, hay, barrels, a grindstone and a cart of crates.
            int yx0 = shop.x1 + 2, yx1 = c.W - 1;
            if (yx1 - yx0 >= 2) {
                c.fenceRun(yx0, c.zf + 1, yx1, c.D - 1, yx0 + 1, c.zf + 1);
                c.woodpile(yx0 + 1, yx1 - 1, c.D - 2, 3);
                c.put(yx0 + 1, 0, z0, Blocks.HAY_BLOCK, GARDEN);
                c.put(yx0 + 1, 1, z0, Blocks.HAY_BLOCK, GARDEN);
                c.put(yx0 + 2, 0, z0, Blocks.HAY_BLOCK, GARDEN);
                c.put(yx1 - 1, 0, z0 + 2, Blocks.BARREL.defaultBlockState(), GARDEN);
                c.put(yx1 - 1, 0, z0 + 3, Blocks.BARREL.defaultBlockState(), GARDEN);
            } else {
                c.woodpile(shop.x0, shop.x0 + 2, c.D - 1, 2);
                c.put(shop.x1 + 1 < c.W ? shop.x1 + 1 : shop.x1, 0, shop.z0 - 1, Blocks.HAY_BLOCK, GARDEN);
            }
            if (c.b.deep >= 2) {
                // A timber warehouse behind, with a loading door.
                Box store = new Box(1, shop.z1 + 2, Math.min(c.W - 2, width), c.D - 2);
                if (store.d() >= 4) {
                    c.foundation(store, c.p.foundation().defaultBlockState(), floor);
                    c.doorway(store.cx(), 0, store.z0);
                    int st = c.walls(store, 0, 1, w.with(w.upper(), w.upper()), c.p.floor().defaultBlockState());
                    c.roof(store, st, true, w.upper());
                    c.door(store.cx(), store.z0, c.back, DoorHingeSide.LEFT);
                    for (int x = store.x0 + 1; x < store.x1; x++) c.put(x, 0, store.z1 - 1, Blocks.BARREL.defaultBlockState(), FIT);
                }
            }
            c.path(door, c.zf, shop.z0 - 1);
            c.path(door + 1, c.zf, shop.z0 - 1);
            c.scaffoldAround(shop);
            c.jobs = 3 + storeys * 2 + (c.b.wide >= 2 ? 2 : 0) + (c.b.deep >= 2 ? 3 : 0) + (lv >= 3 ? 2 : 0);
            return c.b.wide >= 2 || c.b.deep >= 2 ? "works" : "workshop";
        }

        /**
         * Farming industry: a big timber barn with a hay loft and wide doors, fields of crops in rows with water
         * channels, a scarecrow, hay and a composter; a stone granary from level 2.
         */
        static String farm(Canvas c) {
            int lv = c.level;
            Wall w = Style.wall(c, true);
            BlockState floor = Blocks.COARSE_DIRT.defaultBlockState();
            int z0 = c.zf + 2;
            boolean barnLeft = c.rng.nextBoolean();
            int bw = Math.min(7, c.W - 4), bd = Math.min(6, c.D - z0 - 2);
            Box barn = barnLeft ? new Box(1, z0, bw, z0 + bd - 1) : new Box(c.W - 1 - bw, z0, c.W - 2, z0 + bd - 1);
            c.foundation(barn, c.p.foundation().defaultBlockState(), floor);
            for (int x = barn.cx() - 1; x <= barn.cx() + 1; x++) for (int y = 0; y <= 2; y++) c.doorway(x, y, barn.z0);
            int top = c.walls(barn, 0, 1, w, c.p.floor().defaultBlockState());
            c.roof(barn, top, false, w.upper());
            c.put(barn.cx(), 4, barn.z0, Blocks.HAY_BLOCK, FIT);
            for (int x = barn.x0 + 1; x < barn.x1; x++) c.put(x, 0, barn.z1 - 1, x % 2 == 0 ? Blocks.HAY_BLOCK.defaultBlockState() : Blocks.BARREL.defaultBlockState(), FIT);
            c.put(barn.x0 + 1, 0, barn.z0 + 1, Blocks.COMPOSTER.defaultBlockState(), FIT);
            // Fields fill the rest of the lot: rows of crops with a water channel every fourth row.
            int fx0 = barnLeft ? barn.x1 + 2 : 1, fx1 = barnLeft ? c.W - 2 : barn.x0 - 2;
            int fz0 = c.zf + 2, fz1 = c.D - 2;
            Block[] crops = {Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS};
            if (fx1 - fx0 >= 2) {
                c.fenceRun(fx0 - 1, fz0 - 1, fx1 + 1, fz1 + 1, (fx0 + fx1) / 2, fz0 - 1);
                for (int x = fx0; x <= fx1; x++) {
                    Block crop = crops[Math.floorMod(x / 4 + (int) c.b.seed, crops.length)];
                    for (int z = fz0; z <= fz1; z++) {
                        if (!c.inLot(x, z)) continue;
                        if ((x - fx0) % 4 == 3) { c.put(x, -1, z, Blocks.WATER, GROUND); continue; }
                        c.put(x, -1, z, Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7), GROUND);
                        BlockState st = crop.defaultBlockState();
                        if (crop instanceof CropBlock cb) st = cb.getStateForAge(Math.min(cb.getMaxAge(), 4 + (x + z) % 4));
                        c.put(x, 0, z, st, GARDEN);
                    }
                }
                // A scarecrow in the middle of the field.
                int sx = (fx0 + fx1) / 2 + ((fx1 - fx0) % 4 == 3 ? 1 : 0), sz = (fz0 + fz1) / 2;
                c.put(sx, -1, sz, Blocks.COARSE_DIRT, GROUND);
                c.put(sx, 0, sz, Style.fence(c.p), GARDEN);
                c.put(sx, 1, sz, Blocks.HAY_BLOCK, GARDEN);
                c.put(sx, 2, sz, c.facing(Blocks.CARVED_PUMPKIN, c.front), GARDEN);
            }
            if (lv >= 2 && c.D - (barn.z1 + 2) >= 4) {
                // A round-ish stone granary behind the barn.
                int gx = barn.cx(), gz = Math.min(c.D - 3, barn.z1 + 3);
                for (int y = 0; y <= 4 + lv; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0 && y > 0) continue;
                    c.put(gx + dx, y, gz + dz, y == 4 + lv ? c.log(c.p.post(), Direction.Axis.Y) : Blocks.STONE_BRICKS.defaultBlockState(), FRAME);
                }
                c.hip(new Box(gx - 1, gz - 1, gx + 1, gz + 1), 5 + lv, Style.roof(c.p, c.b.seed)[0], Style.roof(c.p, c.b.seed)[1]);
            }
            c.put(barnLeft ? barn.x1 + 1 : barn.x0 - 1, 0, barn.z0, Blocks.HAY_BLOCK, GARDEN);
            c.put(barnLeft ? barn.x1 + 1 : barn.x0 - 1, 1, barn.z0, Blocks.HAY_BLOCK, GARDEN);
            for (int x = barn.cx() - 1; x <= barn.cx() + 1; x++) c.path(x, c.zf, barn.z0 - 1);
            c.lampPost(barnLeft ? 0 : c.W - 1, c.zf);
            c.scaffoldAround(barn);
            c.jobs = 4 + (c.b.wide >= 2 ? 3 : 0) + (c.b.deep >= 2 ? 3 : 0) + lv * 2;
            return "farm";
        }

        /**
         * Forestry: an open saw shed on posts, stacks of logs and planks, rows of saplings being replanted, and a
         * charcoal kiln with a chimney from level 2.
         */
        static String lumberYard(Canvas c) {
            int lv = c.level;
            Wall w = Style.wall(c, true);
            int z0 = c.zf + 2;
            int sw = Math.min(7, c.W - 3), sd = Math.min(5, c.D - z0 - 2);
            Box shed = new Box(1, z0, sw, z0 + sd - 1);
            for (int x = shed.x0; x <= shed.x1; x++) for (int z = shed.z0; z <= shed.z1; z++) c.put(x, -1, z, Blocks.COARSE_DIRT, GROUND);
            // Posts at the corners and midpoints, open sides, a roof over all.
            BlockState post = c.log(c.p.post(), Direction.Axis.Y);
            for (int x = shed.x0; x <= shed.x1; x++) for (int z = shed.z0; z <= shed.z1; z++) {
                boolean isPost = (x == shed.x0 || x == shed.x1 || x == shed.cx()) && (z == shed.z0 || z == shed.z1);
                if (isPost) for (int y = 0; y <= 2; y++) c.put(x, y, z, post, FRAME);
                c.put(x, 3, z, shed.edge(x, z) ? c.beam(c.p.post(), z == shed.z0 || z == shed.z1) : w.upper(), FRAME);
            }
            c.masses.add(new int[] {shed.x0, shed.z0, shed.x1, shed.z1, 3});
            c.roof(shed, 3, true, w.upper());
            // The saw bench and workbench under the shed.
            c.put(shed.cx(), 0, shed.cz(), Blocks.STONECUTTER.defaultBlockState(), FIT);
            c.put(shed.cx() - 1, 0, shed.cz(), c.log(Blocks.STRIPPED_OAK_LOG, Direction.Axis.X), FIT);
            c.put(shed.cx() + 1, 0, shed.cz(), c.log(Blocks.STRIPPED_OAK_LOG, Direction.Axis.X), FIT);
            c.put(shed.x0 + 1, 0, shed.z1 - 1, Blocks.CRAFTING_TABLE, FIT);
            c.put(shed.x1 - 1, 0, shed.z1 - 1, Blocks.BARREL.defaultBlockState(), FIT);
            // Log stacks and plank piles beside the shed, saplings in rows behind.
            int yx0 = shed.x1 + 2, yx1 = c.W - 2;
            for (int x = yx0; x <= yx1; x++) {
                if ((x - yx0) % 3 == 2) continue;
                c.woodpile(x, x, z0, 2 + (x % 2));
                c.woodpile(x, x, z0 + 1, 1 + (x + 1) % 2);
                if (x % 2 == 0) c.put(x, 0, z0 + 3, c.p.floor(), GARDEN);
            }
            for (int z = shed.z1 + 2; z <= c.D - 2; z += 2) {
                for (int x = 1; x <= c.W - 2; x += 2) {
                    if (!c.inLot(x, z)) continue;
                    c.put(x, -1, z, Blocks.PODZOL, GROUND);
                    c.put(x, 0, z, (x + z) % 5 == 0 ? Blocks.SPRUCE_SAPLING.defaultBlockState() : Blocks.OAK_SAPLING.defaultBlockState(), GARDEN);
                }
            }
            if (lv >= 2) {
                // A charcoal kiln: a stone hump with a smoking chimney.
                int kx = c.W - 3, kz = Math.min(c.D - 3, z0 + 5);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.put(kx + dx, 0, kz + dz, Blocks.COBBLESTONE, FRAME);
                c.put(kx, 1, kz, Blocks.COBBLESTONE, FRAME);
                c.put(kx, 0, kz - 1, c.facing(Blocks.FURNACE, c.front), FIT);
                c.chimney(kx, kz + 1, 4 + lv);
            }
            c.fenceRun(0, c.zf + 1, c.W - 1, c.D - 1, shed.cx(), c.zf + 1);
            c.path(shed.cx(), c.zf, shed.z0 - 1);
            c.lampPost(c.W - 1, c.zf);
            c.jobs = 4 + (c.b.wide >= 2 ? 2 : 0) + (c.b.deep >= 2 ? 2 : 0) + lv * 2;
            return "lumber_yard";
        }

        /**
         * Quarry industry: an open pit cut in terraces down into the ground, a timber crane over it with a block on
         * the chain, rubble heaps and a stone-cutting shed by the road; a second saw and stacks of dressed stone from
         * level 2, a cart track down into the pit at level 3.
         */
        static String quarry(Canvas c) {
            int lv = c.level;
            Wall w = Style.wall(c, true);
            boolean desert = c.p.key().equals("desert");
            BlockState stoneB = desert ? Blocks.CUT_SANDSTONE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
            BlockState rough = desert ? Blocks.SANDSTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
            BlockState rubble = desert ? Blocks.SAND.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
            Wall stone = new Wall(stoneB, w.beam(), rough, w.upper(), null, null, false, false);
            boolean wide = c.W >= 12;
            int z0 = c.zf + 2;
            // The stone-cutting shed by the road.
            Box shed = wide ? new Box(1, z0, 5, Math.min(z0 + 3, c.D - 2)) : new Box(1, z0, Math.min(5, c.W - 2), Math.min(z0 + 2, c.D - 2));
            c.foundation(shed, c.p.foundation().defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
            int door = shed.cx();
            c.doorway(door, 0, shed.z0);
            int top = c.walls(shed, 0, 1, stone, c.p.floor().defaultBlockState());
            c.roof(shed, top, true, w.upper());
            c.door(door, shed.z0, c.back, DoorHingeSide.LEFT);
            c.put(shed.x0 + 1, 0, shed.z1 - 1, Blocks.STONECUTTER.defaultBlockState(), FIT);
            if (lv >= 2) c.put(shed.x1 - 1, 0, shed.z1 - 1, Blocks.STONECUTTER.defaultBlockState(), FIT);
            c.put(door - 1 >= shed.x0 ? door - 1 : door + 1, 2, shed.z0 - 1, c.lantern(false), FIT);
            c.banner(Math.min(door + 1, shed.x1), 2, shed.z0 - 1, DyeColor.GRAY, c.front);
            c.scaffoldAround(shed);
            // The pit: terraces a block deep each, deepest in the middle, floored with rough stone.
            Box pit = wide ? new Box(shed.x1 + 2, z0, c.W - 2, c.D - 2) : new Box(1, shed.z1 + 2, c.W - 2, c.D - 2);
            if (pit.w() >= 3 && pit.d() >= 3) {
                int depth = Math.min(3, (Math.min(pit.w(), pit.d()) + 1) / 2);
                for (int x = pit.x0; x <= pit.x1; x++) for (int z = pit.z0; z <= pit.z1; z++) {
                    int ring = Math.min(Math.min(x - pit.x0, pit.x1 - x), Math.min(z - pit.z0, pit.z1 - z));
                    int d = Math.min(depth, ring + 1);
                    for (int y = -1; y >= -d; y--) c.dig(x, y, z);
                    c.put(x, -d - 1, z, (x * 7 + z * 3) % 5 == 0 ? rubble : rough, GROUND);
                }
                // A cart track down the long side at level 3.
                if (lv >= 3) {
                    int tx = pit.x0 + 1;
                    for (int z = pit.z0; z <= pit.z1; z++) {
                        int ring = Math.min(Math.min(tx - pit.x0, pit.x1 - tx), Math.min(z - pit.z0, pit.z1 - z));
                        c.put(tx, -Math.min(depth, ring + 1), z, Blocks.RAIL, FIT);
                    }
                }
                // The crane: a post at the pit's edge, a jib out over it, a chain and a block of stone on the hook.
                int px = wide ? pit.x0 - 1 : pit.x0, pz = wide ? pit.cz() : pit.z0 - 1;
                Direction out = wide ? c.right : c.back;
                for (int y = 0; y <= 4; y++) c.put(px, y, pz, c.log(c.p.post(), Direction.Axis.Y), FRAME);
                int jib = Math.min(3, (wide ? pit.w() : pit.d()) / 2 + 1);
                int jx = px, jz = pz;
                for (int k = 1; k <= jib; k++) {
                    jx = px + (wide ? k : 0);
                    jz = pz + (wide ? 0 : k);
                    c.put(jx, 5, jz, c.log(c.p.post(), wide ? c.right.getAxis() : c.back.getAxis()), FRAME);
                }
                c.put(px - (wide ? 1 : 0), 5, pz - (wide ? 0 : 1), Blocks.COBBLESTONE_WALL, FRAME); // counterweight
                c.put(jx, 4, jz, Blocks.IRON_CHAIN.defaultBlockState(), FIT);
                c.put(jx, 3, jz, Blocks.IRON_CHAIN.defaultBlockState(), FIT);
                c.put(jx, 2, jz, stoneB, FIT);
                c.put(px - (wide ? 0 : 1), 0, pz - (wide ? 1 : 0), Blocks.BARREL.defaultBlockState(), GARDEN);
                c.put(px + (out == c.right ? 0 : 1), 0, pz + (out == c.right ? 1 : 0), Blocks.COMPOSTER, GARDEN);
            }
            // Rubble heaps and dressed stone along the front, clear of the door.
            for (int x = 0; x < c.W; x++) {
                if (!c.inLot(x, c.zf + 1) || Math.abs(x - door) <= 1 || (pit.contains(x, c.zf + 1))) continue;
                int h = (x * 5) % 3;
                if (h == 0) continue;
                c.put(x, 0, c.zf + 1, x % 2 == 0 ? rubble : rough, GARDEN);
                if (h == 2) c.put(x, 1, c.zf + 1, x % 4 == 0 && lv >= 2 ? stoneB : rubble, GARDEN);
            }
            c.path(door, c.zf, shed.z0 - 1);
            c.jobs = 4 + (c.b.wide >= 2 ? 2 : 0) + (c.b.deep >= 2 ? 2 : 0) + lv * 2;
            return "quarry";
        }

        /** Leisure: an inn with a beer garden: tables and benches under strings of lanterns, barrels stacked by the door. */
        static String tavern(Canvas c) {
            inn(c);
            // The beer garden: lanterns hung on fence posts along the front, barrels stacked high.
            for (int x = 0; x < c.W; x += 4) {
                if (!c.inLot(x, c.zf + 1)) continue;
                c.put(x, 0, c.zf + 1, Style.fence(c.p), GARDEN);
                c.put(x, 1, c.zf + 1, Style.fence(c.p), GARDEN);
                c.put(x, 2, c.zf + 1, c.lantern(false), GARDEN);
            }
            c.put(1, 0, c.D - 2, Blocks.BARREL.defaultBlockState(), GARDEN);
            c.put(1, 1, c.D - 2, Blocks.BARREL.defaultBlockState(), GARDEN);
            c.put(2, 0, c.D - 2, Blocks.BARREL.defaultBlockState(), GARDEN);
            c.jobs += 2;
            return "tavern";
        }

        /**
         * Offices: a guild hall with a stone ground floor of tall windows, a timbered upper floor, a hipped roof
         * with dormers, steps up to double doors, and a bell cupola; a corner tower and a garden on a big plot.
         */
        static String guildHall(Canvas c) {
            int lv = c.level;
            Wall w = Style.wall(c, true);
            BlockState stoneB = c.p.key().equals("desert") ? Blocks.CUT_SANDSTONE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
            Wall stone = new Wall(stoneB, w.beam(), stoneB, stoneB, Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), null, false, true);
            BlockState floor = c.p.floor().defaultBlockState();
            boolean big = c.b.wide >= 2;
            int z0 = c.zf + 2;
            Box hall = big ? new Box(2, z0, c.W - 3, Math.min(c.D - 2, z0 + 6)) : new Box(1, z0, 6, Math.min(c.D - 2, z0 + 5));
            c.foundation(hall, stoneB, floor);
            int door = hall.cx();
            c.doorway(door, 0, hall.z0);
            if (big) c.doorway(door + 1, 0, hall.z0);
            int storeys = 1 + (lv >= 2 ? 1 : 0) + (big && lv >= 3 ? 1 : 0);
            int top = c.walls(hall, 0, 1, stone, floor);
            if (storeys > 1) top = c.walls(hall, STOREY, storeys - 1, w, floor);
            Block[] r = Style.roof(c.p, c.b.seed);
            int ridge = c.p.flatRoof() ? c.flat(hall, top, c.p.stone().defaultBlockState(), Style.wall(c.p).defaultBlockState(), Blocks.LANTERN.defaultBlockState())
                    : c.hip(hall, top, r[0], r[1]);
            c.door(door, hall.z0, c.back, DoorHingeSide.LEFT);
            if (big) c.door(door + 1, hall.z0, c.back, DoorHingeSide.RIGHT);
            // Steps and a pediment over the entrance.
            Block stairB = c.p.key().equals("desert") ? Blocks.SMOOTH_SANDSTONE_STAIRS : Blocks.STONE_BRICK_STAIRS;
            for (int x = door - 1; x <= door + (big ? 2 : 1); x++) c.put(x, -1, hall.z0 - 1, c.stairs(stairB, c.back, false), GROUND);
            for (int x = door - 1; x <= door + (big ? 2 : 1); x++) c.put(x, 2, hall.z0 - 1, c.slab(c.p.key().equals("desert")
                    ? Blocks.SMOOTH_SANDSTONE_SLAB : Blocks.STONE_BRICK_SLAB, SlabType.BOTTOM), FIT);
            c.banner(door - 2, 2, hall.z0 - 1, DyeColor.BLUE, c.front);
            c.banner(door + (big ? 3 : 2), 2, hall.z0 - 1, DyeColor.BLUE, c.front);
            if (lv >= 3 && !c.p.flatRoof()) cupola(c, hall.cx(), hall.cz(), ridge, true);
            c.put(hall.x0 + 1, 0, hall.z1 - 1, c.facing(Blocks.LECTERN, c.front), FIT);
            c.put(hall.x1 - 1, 0, hall.z1 - 1, Blocks.CARTOGRAPHY_TABLE, FIT);
            for (int x = hall.x0 + 1; x < hall.x1; x++) c.soft(x, 0, hall.z1 - 1, Blocks.BOOKSHELF.defaultBlockState(), FIT);
            if (big && c.b.deep >= 2) {
                // A corner tower with a pyramid roof and a banner, and a formal garden behind.
                Box tower = new Box(c.W - 5, hall.z1 + 1, c.W - 2, hall.z1 + 4);
                c.foundation(tower, stoneB, floor);
                int tt = c.walls(tower, 0, 3 + (lv >= 3 ? 1 : 0), stone, floor);
                if (!c.p.flatRoof()) c.hip(tower, tt, r[0], r[1]);
                c.banner(tower.x0 - 1, tt - 1, tower.z0 + 1, DyeColor.BLUE, c.left);
                int gz0 = hall.z1 + 2, gz1 = c.D - 2;
                Homes.fountain(c, (2 + tower.x0 - 2) / 2, (gz0 + gz1) / 2);
                c.hedge(1, tower.x0 - 2, gz1);
                c.tree(2, gz0 + 1, 5);
                c.flowers(1, gz0, tower.x0 - 2, gz1 - 1, 0.2f);
            }
            c.lampPost(hall.x0 - 1 >= 0 ? hall.x0 - 1 : 0, c.zf + 1);
            c.lampPost(hall.x1 + 1 < c.W ? hall.x1 + 1 : c.W - 1, c.zf + 1);
            c.hedge(0, door - 2, c.zf);
            c.hedge(door + (big ? 3 : 2), c.W - 1, c.zf);
            c.path(door, c.zf, hall.z0 - 2);
            if (big) c.path(door + 1, c.zf, hall.z0 - 2);
            c.scaffoldAround(hall);
            c.jobs = 6 + storeys * (big ? 6 : 3) + (lv >= 3 ? 4 : 0);
            return big ? "guild" : "counting_house";
        }
    }

    // ================================================================ services and utilities

    static final class Civic {
        static String build(Canvas c, ServiceType s) {
            c.jobs = s.jobs;
            if (s.landmark()) c.furnish = false;
            return switch (s) {
                case WINDMILL -> windmill(c);
                case WATERMILL -> watermill(c);
                case WELL -> well(c);
                case WATER_TOWER -> waterTower(c);
                case CLINIC -> clinic(c);
                case FIRE_STATION -> fireStation(c);
                case GUARD_HOUSE -> guardHouse(c);
                case SCHOOL -> school(c);
                case COMPOST_YARD -> compostYard(c);
                case PARK -> park(c);
                case FOUNDERS_FOUNTAIN -> foundersFountain(c);
                case CLOCK_TOWER -> clockTower(c);
                case CHAPEL -> chapel(c);
                case COACH_HOUSE -> coachHouse(c);
                case GREAT_LIBRARY -> greatLibrary(c);
                case CATHEDRAL -> cathedral(c);
                case CASTLE_KEEP -> castleKeep(c);
            };
        }

        /** A stone-based post mill: two storeys, a hipped cap, big cloth sails on the road side, sacks of grain. */
        static String windmill(Canvas c) {
            Wall w = Style.wall(c, true);
            BlockState stoneB = c.p.foundation().defaultBlockState();
            Wall base = new Wall(Blocks.STONE_BRICKS.defaultBlockState(), w.beam(), stoneB, stoneB, null, null, false, false);
            int z0 = c.zf + 3;
            Box box = new Box(2, z0, 6, Math.min(c.D - 1, z0 + 4));
            c.foundation(box, stoneB, c.p.floor().defaultBlockState());
            c.doorway(4, 0, box.z0);
            c.walls(box, 0, 1, base, c.p.floor().defaultBlockState());
            int top = c.walls(box, STOREY, 1, w.with(w.upper(), w.upper()), c.p.floor().defaultBlockState());
            Block[] r = Style.roof(c.p, c.b.seed);
            c.hip(box, top, r[0], r[1]);
            c.door(4, box.z0, c.back, DoorHingeSide.LEFT);
            // Sails: a hub on the front wall and four arms, each a spar with a cloth on one side.
            int hx = 4, hy = 6, hz = box.z0 - 1;
            c.put(hx, hy, hz, c.log(c.p.post(), c.back.getAxis()), FIT);
            BlockState spar = Style.fence(c.p).defaultBlockState(), cloth = Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState();
            for (int k = 1; k <= 4; k++) {
                c.put(hx, hy + k, hz - 1, spar, FIT);
                c.put(hx + 1, hy + k, hz - 1, cloth, FIT);
                if (hy - k >= 1) { c.put(hx, hy - k, hz - 1, spar, FIT); c.put(hx - 1, hy - k, hz - 1, cloth, FIT); }
                if (k <= 4 && hx + k < c.W) { c.put(hx + k, hy, hz - 1, spar, FIT); c.put(hx + k, hy - 1, hz - 1, cloth, FIT); }
                if (hx - k >= 0) { c.put(hx - k, hy, hz - 1, spar, FIT); c.put(hx - k, hy + 1, hz - 1, cloth, FIT); }
            }
            c.put(hx, hy, hz - 1, c.log(c.p.post(), c.back.getAxis()), FIT);
            c.put(box.x0 - 1, 0, box.z0 + 1, Blocks.HAY_BLOCK, GARDEN);
            c.put(box.x0 - 1, 0, box.z0 + 2, Blocks.HAY_BLOCK, GARDEN);
            c.put(box.x0 - 1, 1, box.z0 + 1, Blocks.HAY_BLOCK, GARDEN);
            c.put(box.x1 + 1, 0, box.z1, Blocks.BARREL.defaultBlockState(), GARDEN);
            c.put(box.x0 + 1, 0, box.z1 - 1, Blocks.GRINDSTONE.defaultBlockState(), FIT);
            c.path(4, c.zf, box.z0 - 1);
            c.flowers(0, c.zf, c.W - 1, box.z0 - 3, 0.2f);
            c.scaffoldAround(box);
            return "windmill";
        }

        /** A mill house with a water wheel turning in a stream channel beside it. */
        static String watermill(Canvas c) {
            Wall w = Style.wall(c, true);
            BlockState floor = c.p.floor().defaultBlockState();
            int z0 = c.zf + 2;
            Box box = new Box(1, z0, 7, Math.min(c.D - 2, z0 + 4));
            c.foundation(box, w.plinth(), floor);
            c.doorway(box.cx(), 0, box.z0);
            int top = c.walls(box, 0, 2, w, floor);
            int ridge = c.roof(box, top, true, w.upper());
            c.door(box.cx(), box.z0, c.back, DoorHingeSide.LEFT);
            c.doorHood(box.cx(), box.z0);
            c.chimney(box.x0 - 1 >= 0 ? box.x0 - 1 : box.x0, box.z1 - 1, ridge + 1);
            // The wheel: a ring of planks and spokes in the plane across the lot, its axle into the wall.
            int wx = box.x1 + 2, wz = box.cz(), wy = 2, rad = 3;
            for (int x = box.x1 + 1; x <= c.W - 1; x++) for (int z = box.z0 - 1; z <= box.z1 + 1; z++) c.put(x, -1, z, Blocks.WATER, GROUND);
            for (int dz = -rad; dz <= rad; dz++) for (int dy = -rad; dy <= rad; dy++) {
                double d = Math.hypot(dz, dy);
                if (Math.abs(d - rad) < 0.55) c.put(wx, wy + dy, wz + dz, c.p.floor().defaultBlockState(), FRAME);
                else if (d < rad - 0.5 && (dz == 0 || dy == 0)) c.put(wx, wy + dy, wz + dz, Style.fence(c.p).defaultBlockState(), FRAME);
            }
            c.put(box.x1 + 1, wy, wz, c.log(c.p.post(), c.right.getAxis()), FRAME);
            c.put(wx, wy, wz, c.log(c.p.post(), c.right.getAxis()), FRAME);
            c.put(box.x0 + 1, 0, box.z1 - 1, Blocks.GRINDSTONE.defaultBlockState(), FIT);
            c.put(box.x0 + 2, 0, box.z1 - 1, Blocks.HAY_BLOCK, FIT);
            c.path(box.cx(), c.zf, box.z0 - 1);
            c.scaffoldAround(box);
            return "watermill";
        }

        /** The village well in a small paved square with benches, flower pots and lamps. */
        static String well(Canvas c) {
            int cx = c.W / 2 - 1, cz = (c.zf + c.D) / 2;
            for (int x = 0; x < c.W; x++) for (int z = c.zf; z < c.D; z++) {
                c.put(x, -1, z, ((x * 7 + z * 3) % 5 == 0 ? Blocks.MOSSY_COBBLESTONE : (x + z) % 2 == 0 ? Blocks.COBBLESTONE : Blocks.STONE_BRICKS)
                        .defaultBlockState(), GROUND);
            }
            BlockState rim = Blocks.STONE_BRICKS.defaultBlockState();
            for (int dx = -1; dx <= 2; dx++) for (int dz = -1; dz <= 2; dz++) {
                boolean edge = dx == -1 || dx == 2 || dz == -1 || dz == 2;
                c.put(cx + dx, -2, cz + dz, rim, GROUND);
                if (edge) c.put(cx + dx, 0, cz + dz, rim, FRAME);
                else { c.put(cx + dx, -1, cz + dz, Blocks.WATER, FIT); c.put(cx + dx, 0, cz + dz, Blocks.WATER, FIT); }
            }
            Block[] r = Style.roof(c.p, c.b.seed);
            for (int[] k : new int[][] {{-1, -1}, {2, -1}, {-1, 2}, {2, 2}}) {
                c.put(cx + k[0], 1, cz + k[1], Style.fence(c.p), FRAME);
                c.put(cx + k[0], 2, cz + k[1], Style.fence(c.p), FRAME);
            }
            c.hip(new Box(cx - 1, cz - 1, cx + 2, cz + 2), 3, r[0], r[1]);
            c.put(cx, 2, cz, c.lantern(true), FIT);
            c.bench(1, 2, c.zf + 1, c.back);
            c.bench(c.W - 3, c.W - 2, c.D - 2, c.front);
            c.put(0, 0, c.D - 1, Blocks.POTTED_RED_TULIP, GARDEN);
            c.put(c.W - 1, 0, c.zf, Blocks.POTTED_POPPY, GARDEN);
            c.lampPost(0, c.zf);
            c.lampPost(c.W - 1, c.D - 1);
            c.tree(0, c.D - 3, 4);
            c.scaffoldAround(new Box(cx - 1, cz - 1, cx + 2, cz + 2));
            return "well";
        }

        /** A timber water tower: four log legs with cross bracing, a barrel-stave tank, a cap and a ladder. */
        static String waterTower(Canvas c) {
            int x0 = 2, z0 = Math.max(c.zf + 2, 2), x1 = x0 + 3, z1 = z0 + 3;
            BlockState leg = c.log(c.p.post(), Direction.Axis.Y);
            for (int[] k : new int[][] {{x0, z0}, {x1, z0}, {x0, z1}, {x1, z1}}) {
                c.put(k[0], -1, k[1], c.p.foundation().defaultBlockState(), GROUND);
                for (int y = 0; y <= 6; y++) c.put(k[0], y, k[1], leg, FRAME);
            }
            for (int y : new int[] {2, 5}) {
                for (int x = x0 + 1; x < x1; x++) { c.put(x, y, z0, Style.fence(c.p), FRAME); c.put(x, y, z1, Style.fence(c.p), FRAME); }
                for (int z = z0 + 1; z < z1; z++) { c.put(x0, y, z, Style.fence(c.p), FRAME); c.put(x1, y, z, Style.fence(c.p), FRAME); }
            }
            Box tank = new Box(x0 - 1, z0 - 1, x1 + 1, z1 + 1);
            for (int x = tank.x0; x <= tank.x1; x++) for (int z = tank.z0; z <= tank.z1; z++) {
                c.put(x, 7, z, c.p.floor().defaultBlockState(), FRAME);
                if (tank.edge(x, z)) for (int y = 8; y <= 10; y++) c.put(x, y, z, tank.corner(x, z) ? leg
                        : c.facing(Blocks.BARREL, c.dir(x == tank.x0 ? -1 : x == tank.x1 ? 1 : 0, z == tank.z0 ? -1 : z == tank.z1 ? 1 : 0)), FRAME);
                else c.put(x, 10, z, Blocks.WATER, FIT);
            }
            c.masses.add(new int[] {tank.x0, tank.z0, tank.x1, tank.z1, 10});
            Block[] r = Style.roof(c.p, c.b.seed);
            c.hip(tank, 11, r[0], r[1]);
            for (int y = 0; y <= 6; y++) c.put(x0 + 1, y, z0 - 1, Blocks.LADDER.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, c.front), FIT);
            c.put(x0 + 1, 0, z0, Blocks.AIR.defaultBlockState(), FIT);
            c.put(x1 + 2 < c.W ? x1 + 2 : x1, 0, z1, Blocks.BARREL.defaultBlockState(), GARDEN);
            c.flowers(0, c.zf, c.W - 1, c.D - 1, 0.12f);
            c.scaffoldAround(tank);
            return "water_tower";
        }

        /** A whitewashed clinic with red banners, a porch with benches and a herb garden. */
        static String clinic(Canvas c) {
            Wall w = Style.wall(c, true);
            BlockState plaster = Blocks.DYED_TERRACOTTA.pick(DyeColor.WHITE).defaultBlockState();
            Wall white = w.with(c.p.key().equals("desert") ? w.lower() : plaster, c.p.key().equals("desert") ? w.upper() : plaster);
            BlockState floor = Blocks.BIRCH_PLANKS.defaultBlockState();
            int z0 = c.zf + 3;
            Box box = new Box(2, z0, 11, Math.min(c.D - 2, z0 + 4));
            c.foundation(box, white.plinth(), floor);
            int door = box.cx();
            c.doorway(door, 0, box.z0);
            int top = c.walls(box, 0, 2, white, floor);
            int ridge = c.roof(box, top, true, plaster);
            if (!c.p.flatRoof()) c.dormer(box, top, door, plaster);
            c.door(door, box.z0, c.back, DoorHingeSide.LEFT);
            c.porch(new Box(door - 2, box.z0, door + 2, box.z1), 2);
            c.banner(door - 3, 2, box.z0 - 1, DyeColor.RED, c.front);
            c.banner(door + 3, 2, box.z0 - 1, DyeColor.RED, c.front);
            c.chimney(box.x1 + 1, box.z1 - 1, ridge + 1);
            c.put(box.x0 + 1, 0, box.z1 - 1, Blocks.BREWING_STAND, FIT);
            c.put(box.x0 + 2, 0, box.z1 - 1, Blocks.WATER_CAULDRON.defaultBlockState(), FIT);
            for (int x = box.x1 - 3; x <= box.x1 - 1; x++) c.put(x, 0, box.z1 - 1, Blocks.WOOL.pick(DyeColor.WHITE), FIT);
            // Herb garden: berry bushes and flowers in a fenced bed, benches by the porch.
            int gx0 = 0, gx1 = box.x0 - 1;
            for (int z = z0; z <= box.z1; z++) for (int x = gx0; x <= gx1; x++) if ((x + z) % 2 == 0) c.put(x, 0, z, Blocks.SWEET_BERRY_BUSH, GARDEN);
            c.bench(box.x1 - 2, box.x1 - 1, box.z0 - 2, c.front);
            c.flowers(0, c.zf, c.W - 1, box.z0 - 3, 0.3f);
            c.path(door, c.zf, box.z0 - 3);
            c.lampPost(door - 3, c.zf + 1);
            c.lampPost(door + 3, c.zf + 1);
            c.tree(c.W - 2, c.zf + 1, 4);
            c.scaffoldAround(box);
            return "clinic";
        }

        /** A brick fire station: wide doors, a bell tower, water troughs and a hose rack. */
        static String fireStation(Canvas c) {
            Wall w = Style.wall(c, true);
            BlockState brick = c.p.key().equals("desert") ? Blocks.CUT_SANDSTONE.defaultBlockState() : Blocks.BRICKS.defaultBlockState();
            Wall bricks = new Wall(c.log(c.p.post(), Direction.Axis.Y), w.beam(), brick, brick, c.p.foundation().defaultBlockState(), w.shutter(), false, false);
            BlockState floor = Blocks.STONE_BRICKS.defaultBlockState();
            int z0 = c.zf + 2;
            Box box = new Box(1, z0, 9, Math.min(c.D - 2, z0 + 5));
            c.foundation(box, c.p.foundation().defaultBlockState(), floor);
            for (int x = 3; x <= 6; x++) c.doorway(x, 0, box.z0);
            int top = c.walls(box, 0, 2, bricks, c.p.floor().defaultBlockState());
            c.roof(box, top, true, brick);
            for (int x = 3; x <= 6; x++) c.door(x, box.z0, c.back, x % 2 == 1 ? DoorHingeSide.LEFT : DoorHingeSide.RIGHT);
            // The bell tower at the corner.
            Box tower = new Box(10, z0 + 1, 13, z0 + 4);
            c.foundation(tower, brick, floor);
            c.doorway(11, 0, tower.z0);
            int tt = c.walls(tower, 0, 2, bricks, c.p.floor().defaultBlockState());
            BlockState post = c.log(c.p.post(), Direction.Axis.Y);
            for (int[] k : new int[][] {{tower.x0, tower.z0}, {tower.x1, tower.z0}, {tower.x0, tower.z1}, {tower.x1, tower.z1}}) {
                for (int y = tt + 1; y <= tt + 3; y++) c.put(k[0], y, k[1], post, FRAME);
            }
            for (int x = tower.x0; x <= tower.x1; x++) for (int z = tower.z0; z <= tower.z1; z++) c.put(x, tt + 4, z, brick, FRAME);
            c.masses.add(new int[] {tower.x0, tower.z0, tower.x1, tower.z1, tt + 4});
            Block[] r = Style.roof(c.p, c.b.seed);
            c.hip(tower, tt + 5, r[0], r[1]);
            c.put(11, tt + 3, tower.z0 + 1, Blocks.BELL.defaultBlockState().setValue(BellBlock.ATTACHMENT, BellAttachType.CEILING).setValue(BellBlock.FACING, c.front), FIT);
            c.door(11, tower.z0, c.back, DoorHingeSide.LEFT);
            c.banner(2, 6, box.z0 - 1, DyeColor.RED, c.front);
            c.banner(8, 6, box.z0 - 1, DyeColor.RED, c.front);
            // Troughs and a hose rack in the yard.
            c.put(1, 0, box.z0 - 2, Blocks.WATER_CAULDRON.defaultBlockState(), GARDEN);
            c.put(8, 0, box.z0 - 2, Blocks.WATER_CAULDRON.defaultBlockState(), GARDEN);
            for (int y = 0; y <= 2; y++) c.put(c.W - 2, y, c.D - 2, Style.fence(c.p), GARDEN);
            for (int x = 3; x <= 6; x++) c.path(x, c.zf, box.z0 - 1);
            c.lampPost(0, c.zf);
            c.scaffoldAround(box);
            return "fire_station";
        }

        /**
         * The coach house: a long timber stable with two wide coach doors and a hay loft, a water trough and hay by the
         * doors, and a fenced paddock beside it.
         */
        static String coachHouse(Canvas c) {
            Wall w = Style.wall(c, true);
            BlockState floor = Blocks.STONE_BRICKS.defaultBlockState();
            int z0 = c.zf + 2;
            Box box = new Box(1, z0, 10, Math.min(c.D - 2, z0 + 5));
            c.foundation(box, c.p.foundation().defaultBlockState(), floor);
            // One tall stable storey: two coach doors, three blocks wide and as high as the walls, the hay loft in the roof.
            for (int x : new int[] {2, 3, 4, 7, 8, 9}) for (int y = 0; y <= 2; y++) c.doorway(x, y, box.z0);
            int top = c.walls(box, 0, 1, w, c.p.floor().defaultBlockState());
            c.roof(box, top, true, c.p.foundation().defaultBlockState());
            // Lintels over the doors.
            for (int x : new int[] {2, 3, 4, 7, 8, 9}) c.put(x, 3, box.z0, c.log(c.p.post(), Direction.Axis.X), FIT);
            // Stalls inside: hay and troughs along the back wall.
            for (int x = box.x0 + 1; x < box.x1; x++) {
                if (x % 3 == 0) c.put(x, 0, box.z1 - 1, Blocks.HAY_BLOCK, FIT);
                else if (x % 3 == 1) c.put(x, 0, box.z1 - 1, Blocks.WATER_CAULDRON.defaultBlockState(), FIT);
            }
            c.banner(1, 3, box.z0 - 1, DyeColor.BROWN, c.front);
            c.banner(10, 3, box.z0 - 1, DyeColor.BROWN, c.front);
            c.put(5, 2, box.z0 - 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, c.front), FIT);
            c.put(6, 2, box.z0 - 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, c.front), FIT);
            // The yard: a trough and hay by the doors, a paddock fence round the side.
            c.put(5, 0, box.z0 - 2, Blocks.WATER_CAULDRON.defaultBlockState(), GARDEN);
            c.put(6, 0, box.z0 - 2, Blocks.HAY_BLOCK, GARDEN);
            int px0 = box.x1 + 2;
            if (px0 < c.W - 1) {
                for (int x = px0; x < c.W; x++) for (int z = z0; z <= box.z1; z++) {
                    if (x == px0 || x == c.W - 1 || z == z0 || z == box.z1) c.put(x, 0, z, Style.fence(c.p), GARDEN);
                }
                c.put(c.W - 2, 0, z0 + 1, Blocks.HAY_BLOCK, GARDEN);
            }
            for (int x : new int[] {2, 3, 4, 7, 8, 9}) c.path(x, c.zf, box.z0 - 1);
            c.lampPost(0, c.zf);
            c.scaffoldAround(box);
            return "coach_house";
        }

        /** A stone guard tower with battlements, a banner, and a training yard of targets. */
        static String guardHouse(Canvas c) {
            BlockState stoneB = Blocks.STONE_BRICKS.defaultBlockState(), mossy = Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
            Wall stone = new Wall(Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), c.p.post(), stoneB, mossy, Blocks.COBBLESTONE.defaultBlockState(), null, false, false);
            int z0 = c.zf + 2;
            Box box = new Box(1, z0, 5, Math.min(c.D - 2, z0 + 4));
            c.foundation(box, Blocks.COBBLESTONE.defaultBlockState(), c.p.floor().defaultBlockState());
            c.doorway(3, 0, box.z0);
            int top = c.walls(box, 0, 3, stone, c.p.floor().defaultBlockState());
            for (int x = box.x0; x <= box.x1; x++) for (int z = box.z0; z <= box.z1; z++) {
                if (!box.edge(x, z)) c.put(x, top, z, c.p.floor().defaultBlockState(), ROOF);
                else if ((x + z) % 2 == 0) c.put(x, top + 1, z, stoneB, ROOF);
                else c.put(x, top + 1, z, Blocks.STONE_BRICK_WALL, ROOF);
            }
            c.door(3, box.z0, c.back, DoorHingeSide.LEFT);
            c.banner(box.x0 - 1 >= 0 ? box.x0 - 1 : box.x0, top - 1, box.z0 + 2, DyeColor.BLUE, c.left);
            c.banner(3, top - 2, box.z0 - 1, DyeColor.BLUE, c.front);
            c.put(2, 2, box.z0 - 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, c.front), FIT);
            // Training yard: hay dummies with targets, a weapon rack.
            int yx = box.x1 + 1;
            if (yx < c.W - 1) {
                c.put(c.W - 1, 0, z0 + 1, Blocks.HAY_BLOCK, GARDEN);
                c.put(c.W - 1, 1, z0 + 1, Blocks.TARGET, GARDEN);
                c.put(c.W - 1, 0, z0 + 3, Blocks.HAY_BLOCK, GARDEN);
                c.put(c.W - 1, 1, z0 + 3, Blocks.CARVED_PUMPKIN.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, c.left), GARDEN);
                c.put(yx, 0, c.D - 2, Style.fence(c.p), GARDEN);
                c.put(yx, 1, c.D - 2, Blocks.LANTERN, GARDEN);
            }
            c.path(3, c.zf, box.z0 - 1);
            c.scaffoldAround(box);
            return "guard_house";
        }

        /** A long schoolhouse with a bell cupola, and a fenced schoolyard with a tree, benches and a garden plot. */
        static String school(Canvas c) {
            Wall w = Style.wall(c, true);
            BlockState floor = c.p.floor().defaultBlockState();
            int z0 = c.zf + 2;
            Box main = new Box(1, z0, c.W - 2, z0 + 5);
            Box wing = new Box(1, main.z1 + 1, 6, main.z1 + 5);
            c.foundation(main, w.plinth(), floor);
            c.foundation(wing, w.plinth(), floor);
            int door = main.cx();
            c.doorway(door, 0, main.z0);
            c.doorway(door + 1, 0, main.z0);
            int top = c.walls(main, 0, 2, w.with(w.lower(), w.upper()), floor);
            int tw = c.walls(wing, 0, 1, w, floor);
            int ridge = c.roof(main, top, true, w.upper());
            c.roof(wing, tw, false, w.upper());
            if (!c.p.flatRoof()) {
                c.dormer(main, top, main.x0 + 3, w.upper());
                c.dormer(main, top, main.x1 - 3, w.upper());
                Trade.cupola(c, door, main.cz(), ridge, true);
            }
            c.door(door, main.z0, c.back, DoorHingeSide.LEFT);
            c.door(door + 1, main.z0, c.back, DoorHingeSide.RIGHT);
            c.porch(new Box(door - 1, main.z0, door + 2, main.z1), 1);
            c.banner(door - 2, 6, main.z0 - 1, DyeColor.LIGHT_BLUE, c.front);
            c.banner(door + 3, 6, main.z0 - 1, DyeColor.LIGHT_BLUE, c.front);
            c.chimney(main.x0 - 1 >= 0 ? main.x0 - 1 : 0, main.z0 + 2, ridge);
            for (int x = main.x0 + 1; x < main.x1; x += 2) c.put(x, 0, main.z1 - 1, c.facing(Blocks.LECTERN, c.front), FIT);
            for (int x = main.x0 + 1; x < main.x1; x++) c.soft(x, 4, main.z1 - 1, Blocks.BOOKSHELF.defaultBlockState(), FIT);
            // Schoolyard.
            int yx0 = wing.x1 + 2, yz0 = main.z1 + 2, yz1 = c.D - 1;
            c.fenceRun(yx0 - 1, main.z1 + 1, c.W - 1, yz1, -1, -1);
            c.tree(c.W - 4, (yz0 + yz1) / 2, 5);
            c.bench(yx0, yx0 + 1, yz1 - 1, c.front);
            c.plot(yx0 + 1, yz0, yx0 + 3, yz0 + 2);
            c.flowers(0, c.zf, c.W - 1, z0 - 2, 0.25f);
            c.lampPost(door - 3, c.zf + 1);
            c.lampPost(door + 4, c.zf + 1);
            c.path(door, c.zf, main.z0 - 2);
            c.path(door + 1, c.zf, main.z0 - 2);
            c.scaffoldAround(main);
            return "school";
        }

        /** A fenced compost yard: rows of composters, a lean-to shed, hay, and a pumpkin and melon patch. */
        static String compostYard(Canvas c) {
            Wall w = Style.wall(c, true);
            c.fenceRun(0, c.zf + 1, c.W - 1, c.D - 1, 3, c.zf + 1);
            for (int x = 2; x < c.W - 6; x += 2) {
                c.put(x, 0, c.zf + 3, Blocks.COMPOSTER, FIT);
                c.put(x, 0, c.zf + 5, Blocks.COMPOSTER, FIT);
            }
            Box shed = new Box(c.W - 5, c.zf + 2, c.W - 2, c.D - 2);
            c.foundation(shed, c.p.foundation().defaultBlockState(), c.p.floor().defaultBlockState());
            c.doorway(shed.cx(), 0, shed.z0);
            int t = c.walls(shed, 0, 1, w, c.p.floor().defaultBlockState());
            c.roof(shed, t, false, w.upper());
            c.door(shed.cx(), shed.z0, c.back, DoorHingeSide.LEFT);
            c.put(shed.x0 - 1, 0, shed.z1, Blocks.HAY_BLOCK, GARDEN);
            c.put(shed.x0 - 1, 1, shed.z1, Blocks.HAY_BLOCK, GARDEN);
            for (int x = 1; x < c.W - 6; x++) {
                c.put(x, -1, c.D - 2, Blocks.COARSE_DIRT, GROUND);
                if (x % 3 == 0) c.put(x, 0, c.D - 2, Blocks.PUMPKIN, GARDEN);
                else if (x % 3 == 1) c.put(x, 0, c.D - 2, Blocks.MELON, GARDEN);
            }
            c.path(3, c.zf, c.zf + 1);
            c.scaffoldAround(shed);
            return "compost_yard";
        }

        /** A village green: crossing paths, a fountain, trees, flower beds, benches and lamps inside a hedge. */
        static String park(Canvas c) {
            int cx = c.W / 2, cz = (c.zf + c.D) / 2;
            for (int x = 0; x < c.W; x++) c.put(x, -1, cz, Blocks.GRAVEL, GROUND);
            for (int z = c.zf; z < c.D; z++) { c.put(cx, -1, z, Blocks.GRAVEL, GROUND); c.put(cx - 1, -1, z, Blocks.GRAVEL, GROUND); }
            Homes.fountain(c, cx, cz);
            c.put(cx, 1, cz, Blocks.STONE_BRICK_WALL, FRAME);
            c.put(cx, 2, cz, c.lantern(false), FIT);
            c.tree(2, c.zf + 2, 5);
            c.tree(c.W - 3, c.D - 2, 5);
            c.tree(2, c.D - 2, 4);
            c.tree(c.W - 3, c.zf + 2, 4);
            c.bench(cx - 4, cx - 3, cz - 1, c.back);
            c.bench(cx + 2, cx + 3, cz + 1, c.front);
            c.lampPost(cx - 2, c.zf);
            c.lampPost(cx + 1, c.D - 1);
            c.flowers(0, c.zf, c.W - 1, c.D - 1, 0.35f);
            c.hedge(0, cx - 3, c.D - 1);
            c.hedge(cx + 2, c.W - 1, c.D - 1);
            c.scaffoldAround(new Box(cx - 1, cz - 1, cx + 1, cz + 1));
            return "park";
        }

        // ---------------------------------------------------------------- landmarks

        /** A paved plaza round a tiered fountain with an obelisk, trees in planters, benches, banners and lamps. */
        static String foundersFountain(Canvas c) {
            int cx = c.W / 2, cz = (c.zf + c.D) / 2;
            for (int x = 0; x < c.W; x++) for (int z = c.zf; z < c.D; z++) {
                boolean ring = Math.abs(x - cx + 0.5) + Math.abs(z - cz) < 6;
                c.put(x, -1, z, (ring ? ((x + z) % 2 == 0 ? Blocks.POLISHED_ANDESITE : Blocks.STONE_BRICKS)
                        : (x * 5 + z * 3) % 7 == 0 ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState(), GROUND);
            }
            BlockState rim = Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP);
            // The basin: 6 x 4 with a slab rim, water inside, a pedestal in the middle.
            for (int x = cx - 3; x <= cx + 2; x++) for (int z = cz - 2; z <= cz + 1; z++) {
                boolean edge = x == cx - 3 || x == cx + 2 || z == cz - 2 || z == cz + 1;
                c.put(x, -2, z, Blocks.STONE_BRICKS, GROUND);
                if (edge) c.put(x, 0, z, rim, FRAME);
                else { c.put(x, -1, z, Blocks.WATER, FIT); c.put(x, 0, z, Blocks.WATER, FIT); }
            }
            for (int x = cx - 1; x <= cx; x++) for (int z = cz - 1; z <= cz; z++) {
                c.put(x, -1, z, Blocks.STONE_BRICKS, GROUND);
                c.put(x, 0, z, Blocks.CHISELED_STONE_BRICKS, FRAME);
                c.put(x, 1, z, Blocks.STONE_BRICK_WALL, FRAME);
                c.put(x, 2, z, Blocks.STONE_BRICK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), FRAME);
            }
            // The obelisk on the pedestal, crowned with a lantern.
            c.put(cx, 2, cz, Blocks.CHISELED_STONE_BRICKS, FRAME);
            for (int y = 3; y <= 5; y++) c.put(cx, y, cz, Blocks.STONE_BRICK_WALL, FRAME);
            c.put(cx, 6, cz, c.lantern(false), FIT);
            // Trees in planters at the corners, benches facing the fountain, banners on posts, lamps.
            for (int[] k : new int[][] {{1, c.zf + 1}, {c.W - 2, c.zf + 1}, {1, c.D - 2}, {c.W - 2, c.D - 2}}) {
                c.put(k[0], -1, k[1], Blocks.GRASS_BLOCK, GROUND);
                c.tree(k[0], k[1], 4);
            }
            c.bench(cx - 2, cx + 1, cz - 4, c.back);
            c.bench(cx - 2, cx + 1, cz + 3, c.front);
            for (int[] k : new int[][] {{cx - 6, cz}, {cx + 5, cz}}) {
                if (!c.inLot(k[0], k[1])) continue;
                for (int y = 0; y <= 3; y++) c.put(k[0], y, k[1], Style.fence(c.p), FRAME);
                c.put(k[0], 4, k[1], c.lantern(false), FIT);
                c.banner(k[0], 3, k[1] - 1, DyeColor.YELLOW, c.front);
            }
            c.flowers(0, c.zf, c.W - 1, c.D - 1, 0.08f);
            c.scaffoldAround(new Box(cx - 3, cz - 2, cx + 2, cz + 1));
            return "founders_fountain";
        }

        /** A stone and timber clock tower with a clock face on every side, an open belfry and a tall spire. */
        static String clockTower(Canvas c) {
            int z0 = Math.max(c.zf + 2, 2);
            Box box = new Box(2, z0, 6, z0 + 4);
            BlockState stoneB = Blocks.STONE_BRICKS.defaultBlockState();
            Wall stone = new Wall(Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), c.p.post(), stoneB, stoneB, Blocks.COBBLESTONE.defaultBlockState(), null, false, true);
            Wall timber = Style.wall(c, false);
            c.foundation(box, Blocks.COBBLESTONE.defaultBlockState(), c.p.floor().defaultBlockState());
            c.doorway(4, 0, box.z0);
            c.walls(box, 0, 2, stone, c.p.floor().defaultBlockState());
            int top = c.walls(box, 2 * STOREY, 1, timber.with(timber.upper(), timber.upper()), c.p.floor().defaultBlockState());
            // Clock faces under the belfry: a white dial, a dark hub and two hands, on all four sides.
            int fy = top - 2;
            BlockState dial = Blocks.WOOL.pick(DyeColor.WHITE).defaultBlockState(), hand = Blocks.WOOL.pick(DyeColor.BLACK).defaultBlockState();
            for (int[] side : new int[][] {{0, -1}, {0, 1}, {-1, 0}, {1, 0}}) {
                for (int a = -1; a <= 1; a++) for (int dy = -1; dy <= 1; dy++) {
                    int x = side[0] == 0 ? box.cx() + a : side[0] < 0 ? box.x0 - 1 : box.x1 + 1;
                    int z = side[1] == 0 ? box.cz() + a : side[1] < 0 ? box.z0 - 1 : box.z1 + 1;
                    boolean centre = a == 0 && dy == 0, twelve = a == 0 && dy == 1, three = a == (side[0] + side[1] > 0 ? -1 : 1) && dy == 0;
                    c.put(x, fy + dy, z, centre || twelve || three ? hand : dial, FIT);
                }
            }
            // Belfry: posts at the corners, open sides, a bell.
            BlockState post = c.log(c.p.post(), Direction.Axis.Y);
            for (int[] k : new int[][] {{box.x0, box.z0}, {box.x1, box.z0}, {box.x0, box.z1}, {box.x1, box.z1}}) {
                for (int y = top + 1; y <= top + 3; y++) c.put(k[0], y, k[1], post, FRAME);
            }
            for (int x = box.x0; x <= box.x1; x++) for (int z = box.z0; z <= box.z1; z++) c.put(x, top + 4, z, stoneB, FRAME);
            c.masses.add(new int[] {box.x0, box.z0, box.x1, box.z1, top + 4});
            c.put(box.cx(), top + 3, box.cz(), Blocks.BELL.defaultBlockState().setValue(BellBlock.ATTACHMENT, BellAttachType.CEILING)
                    .setValue(BellBlock.FACING, c.front), FIT);
            Block[] r = Style.roof(c.p, c.b.seed);
            int spire = c.hip(box, top + 5, r[0], r[1]);
            for (int y = spire + 1; y <= spire + 2; y++) c.put(box.cx(), y, box.cz(), Style.fence(c.p), ROOF);
            c.put(box.cx(), spire + 3, box.cz(), c.lantern(false), FIT);
            c.door(4, box.z0, c.back, DoorHingeSide.LEFT);
            c.banner(box.x0 - 1, 6, box.z0 + 2, DyeColor.YELLOW, c.left);
            c.banner(box.x1 + 1, 6, box.z0 + 2, DyeColor.YELLOW, c.right);
            // A small paved square at its foot with benches and lamps.
            for (int x = 0; x < c.W; x++) for (int z = c.zf; z < box.z0; z++) c.put(x, -1, z, ((x + z) % 2 == 0 ? Blocks.STONE_BRICKS : Blocks.POLISHED_ANDESITE).defaultBlockState(), GROUND);
            c.bench(0, 1, c.zf + 1, c.back);
            c.bench(c.W - 2, c.W - 1, c.zf + 1, c.back);
            c.lampPost(0, box.z0 + 1);
            c.lampPost(c.W - 1, box.z0 + 1);
            c.flowers(0, box.z1 + 1, c.W - 1, c.D - 1, 0.3f);
            c.scaffoldAround(box);
            return "clock_tower";
        }

        /** A stone chapel: a nave with stained glass, a steeple with a bell over the door, a walled garden and a lych gate. */
        static String chapel(Canvas c) {
            int z0 = c.zf + 4;
            Box nave = new Box(4, z0, 11, Math.min(c.D - 3, z0 + 10));
            Box tower = new Box(6, z0 - 3, 9, z0);
            BlockState stoneB = c.p.key().equals("desert") ? Blocks.CUT_SANDSTONE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
            Wall stone = new Wall(Blocks.CHISELED_STONE_BRICKS.defaultBlockState(), c.p.post(), stoneB, stoneB, Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), null, false, true);
            BlockState floor = Blocks.POLISHED_ANDESITE.defaultBlockState();
            c.foundation(nave, stoneB, floor);
            c.foundation(tower, stoneB, floor);
            int top = c.walls(nave, 0, 2, stone, Blocks.AIR.defaultBlockState());
            // Stained glass: replace the nave's side windows with coloured panes, two tall.
            DyeColor[] glass = {DyeColor.BLUE, DyeColor.RED, DyeColor.YELLOW, DyeColor.PURPLE};
            for (int z = nave.z0 + 2; z < nave.z1 - 1; z += 2) for (int x : new int[] {nave.x0, nave.x1}) {
                for (int y = 1; y <= 5; y++) {
                    if (y == 3) continue; // the beam between the two storeys
                    c.put(x, y, z, Blocks.STAINED_GLASS_PANE.pick(glass[(z / 2) % glass.length]), FRAME);
                }
            }
            Block[] r = Style.roof(c.p, c.b.seed);
            c.gable(nave, top, false, r[0], r[1], stoneB);
            // The steeple: a square stone tower over the entrance, a belfry, and a tall pointed spire.
            c.doorway(tower.cx(), 0, tower.z0);
            c.doorway(tower.cx() + 1, 0, tower.z0);
            int tt = c.walls(tower, 0, 3, stone, c.p.floor().defaultBlockState());
            BlockState post = Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
            for (int[] k : new int[][] {{tower.x0, tower.z0}, {tower.x1, tower.z0}, {tower.x0, tower.z1}, {tower.x1, tower.z1}}) {
                for (int y = tt + 1; y <= tt + 3; y++) c.put(k[0], y, k[1], post, FRAME);
            }
            for (int x = tower.x0; x <= tower.x1; x++) for (int z = tower.z0; z <= tower.z1; z++) c.put(x, tt + 4, z, stoneB, FRAME);
            c.masses.add(new int[] {tower.x0, tower.z0, tower.x1, tower.z1, tt + 4});
            c.put(tower.cx(), tt + 3, tower.cz(), Blocks.BELL.defaultBlockState().setValue(BellBlock.ATTACHMENT, BellAttachType.CEILING)
                    .setValue(BellBlock.FACING, c.front), FIT);
            int spire = c.hip(tower, tt + 5, r[0], r[1]);
            for (int y = spire + 1; y <= spire + 3; y++) c.put(tower.cx(), y, tower.cz(), Blocks.STONE_BRICK_WALL, ROOF);
            c.put(tower.cx(), spire + 4, tower.cz(), c.lantern(false), FIT);
            c.door(tower.cx(), tower.z0, c.back, DoorHingeSide.LEFT);
            c.door(tower.cx() + 1, tower.z0, c.back, DoorHingeSide.RIGHT);
            // Inside: pews and an altar with candles of lanterns.
            for (int z = nave.z0 + 2; z < nave.z1 - 2; z += 2) {
                for (int x = nave.x0 + 1; x <= nave.x0 + 2; x++) c.put(x, 0, z, c.stairs(Style.stairsOf(c.p), c.back, false), FIT);
                for (int x = nave.x1 - 2; x <= nave.x1 - 1; x++) c.put(x, 0, z, c.stairs(Style.stairsOf(c.p), c.back, false), FIT);
            }
            c.put(nave.cx(), 0, nave.z1 - 1, Blocks.CHISELED_STONE_BRICKS, FIT);
            c.put(nave.cx(), 1, nave.z1 - 1, c.lantern(false), FIT);
            // The walled garden: low stone wall, a lych gate at the path, spruce trees, flowers, benches.
            for (int x = 0; x < c.W; x++) for (int z = c.zf; z < c.D; z++) {
                boolean edge = x == 0 || x == c.W - 1 || z == c.zf || z == c.D - 1;
                if (edge && !(z == c.zf && (x == tower.cx() || x == tower.cx() + 1))) c.put(x, 0, z, Blocks.MOSSY_COBBLESTONE_WALL, GARDEN);
            }
            for (int x = tower.cx() - 1; x <= tower.cx() + 2; x++) {
                c.put(x, 3, c.zf, c.slab(r[1], SlabType.BOTTOM), ROOF);
                if (x == tower.cx() - 1 || x == tower.cx() + 2) for (int y = 0; y <= 2; y++) c.put(x, y, c.zf, Style.fence(c.p), FRAME);
            }
            for (int x = tower.cx(); x <= tower.cx() + 1; x++) c.path(x, c.zf, tower.z0 - 1);
            c.tree(2, c.zf + 3, 5);
            c.tree(c.W - 3, c.zf + 3, 5);
            c.tree(1, c.D - 3, 4);
            c.tree(c.W - 2, c.D - 3, 4);
            c.bench(1, 2, nave.cz(), c.right);
            c.flowers(1, c.zf + 1, c.W - 2, c.D - 2, 0.25f);
            c.scaffoldAround(nave);
            return "chapel";
        }

        // ---------------------------------------------------------------- monuments

        /** The monument's dressed stone: sandstone in the desert, stone brick elsewhere. */
        private static BlockState[] masonry(Canvas c) {
            boolean desert = c.p.key().equals("desert");
            return new BlockState[] {
                    (desert ? Blocks.CUT_SANDSTONE : Blocks.STONE_BRICKS).defaultBlockState(),
                    (desert ? Blocks.CHISELED_SANDSTONE : Blocks.CHISELED_STONE_BRICKS).defaultBlockState(),
                    (desert ? Blocks.SMOOTH_SANDSTONE : Blocks.MOSSY_STONE_BRICKS).defaultBlockState(),
                    (desert ? Blocks.SANDSTONE_WALL : Blocks.STONE_BRICK_WALL).defaultBlockState(),
            };
        }

        /** Every glass pane planned inside a box (lot x/z, any height) becomes {@code to}. */
        private static void reglaze(Canvas c, Box box, java.util.function.Function<BlockPos, BlockState> to) {
            for (int x = box.x0; x <= box.x1; x++) for (int z = box.z0; z <= box.z1; z++) for (int y = 0; y < 40; y++) {
                BlockState s = c.get(x, y, z);
                if (s != null && s.is(Blocks.GLASS_PANE)) c.put(x, y, z, to.apply(c.at(x, y, z)), FRAME);
            }
        }

        /** Battlements on a box at height y: a walk of stone with merlons on every other edge block. */
        private static void crenellate(Canvas c, Box box, int y, BlockState stone, BlockState merlon) {
            for (int x = box.x0; x <= box.x1; x++) for (int z = box.z0; z <= box.z1; z++) {
                c.put(x, y, z, stone, ROOF);
                if (box.edge(x, z) && ((x - box.x0) + (z - box.z0)) % 2 == 0) c.put(x, y + 1, z, merlon, ROOF);
            }
            c.masses.add(new int[] {box.x0, box.z0, box.x1, box.z1, y + 1});
        }

        /**
         * The Great Library: a stone hall of two tall storeys under a hipped roof and lantern cupola, a columned
         * portico on the road, galleries of books round an open reading room, and a formal garden.
         */
        static String greatLibrary(Canvas c) {
            BlockState[] m = masonry(c);
            BlockState stoneB = m[0], trim = m[1], plinth = m[2], column = m[3];
            BlockState floor = Blocks.POLISHED_ANDESITE.defaultBlockState();
            int z0 = c.zf + 5;
            Box hall = new Box(2, z0, c.W - 3, Math.min(c.D - 3, z0 + 8));
            Wall wall = new Wall(trim, c.p.post(), stoneB, Style.wall(c, false).upper(), plinth, null, false, true);
            c.foundation(hall, stoneB, floor);
            int door = hall.cx();
            for (int y = 0; y <= 2; y++) { c.doorway(door, y, hall.z0); c.doorway(door + 1, y, hall.z0); }
            int top = c.walls(hall, 0, 2, wall, c.p.floor().defaultBlockState());
            // An open reading room in the middle: the upper floor is a gallery round it, railed with fences.
            Box well = new Box(hall.x0 + 4, hall.z0 + 4, hall.x1 - 4, hall.z1 - 4);
            for (int x = well.x0 - 1; x <= well.x1 + 1; x++) for (int z = well.z0 - 1; z <= well.z1 + 1; z++) {
                if (well.contains(x, z)) c.erase(x, STOREY - 1, z);
                else c.put(x, STOREY, z, Style.fence(c.p), FIT);
            }
            Block[] r = Style.roof(c.p, c.b.seed);
            int ridge = c.p.flatRoof() ? c.flat(hall, top + 1, stoneB, column, trim) : c.hip(hall, top + 1, r[0], r[1]);
            if (!c.p.flatRoof()) {
                for (int x = hall.x0; x <= hall.x1; x++) for (int z = hall.z0; z <= hall.z1; z++) if (!hall.edge(x, z)) c.put(x, top, z, c.p.floor().defaultBlockState(), FRAME);
            }
            Trade.cupola(c, hall.cx(), hall.cz(), ridge, false);
            c.door(door, hall.z0, c.back, DoorHingeSide.LEFT);
            c.door(door + 1, hall.z0, c.back, DoorHingeSide.RIGHT);
            // Books: shelves along the inside of every wall on both floors (not over windows or doors).
            for (int x = hall.x0 + 1; x < hall.x1; x++) for (int z = hall.z0 + 1; z < hall.z1; z++) {
                boolean byWall = x == hall.x0 + 1 || x == hall.x1 - 1 || z == hall.z0 + 1 || z == hall.z1 - 1;
                if (!byWall || z == hall.z0 + 1 && Math.abs(x - door - 0.5) < 2) continue;
                for (int y : new int[] {0, 1, STOREY, STOREY + 1}) c.soft(x, y, z, Blocks.BOOKSHELF.defaultBlockState(), FIT);
                if (z == hall.z1 - 1 || x == hall.x0 + 1 || x == hall.x1 - 1) c.soft(x, 2, z, Blocks.CHISELED_BOOKSHELF.defaultBlockState(), FIT);
            }
            // The reading room: an enchanting table under the cupola, lecterns and map tables round it.
            c.put(hall.cx(), 0, hall.cz(), Blocks.ENCHANTING_TABLE, FIT);
            c.put(hall.cx() - 2, 0, hall.cz(), c.facing(Blocks.LECTERN, c.right), FIT);
            c.put(hall.cx() + 3, 0, hall.cz(), c.facing(Blocks.LECTERN, c.left), FIT);
            c.put(hall.cx(), 0, hall.cz() + 2, Blocks.CARTOGRAPHY_TABLE, FIT);
            c.put(hall.cx() + 1, 0, hall.cz() - 2, Blocks.CARTOGRAPHY_TABLE, FIT);
            c.put(hall.cx(), top - 1, hall.cz(), c.lantern(true), FIT);
            // The portico: a row of columns carrying a stone entablature, steps up to it.
            int pz0 = hall.z0 - 3;
            for (int x = hall.x0; x <= hall.x1; x++) {
                for (int z = pz0; z < hall.z0; z++) c.put(x, -1, z, stoneB, GROUND);
                c.put(x, -1, pz0 - 1, c.stairs(c.p.key().equals("desert") ? Blocks.SANDSTONE_STAIRS : Blocks.STONE_BRICK_STAIRS, c.back, false), GROUND);
                boolean col = (x - hall.x0) % 3 == 0 && x != door && x != door + 1;
                if (col) {
                    c.put(x, 0, pz0, trim, FRAME);
                    for (int y = 1; y <= 4; y++) c.put(x, y, pz0, column, FRAME);
                }
                for (int z = pz0; z < hall.z0; z++) c.put(x, 5, z, z == pz0 ? trim : stoneB, FRAME);
                c.put(x, 6, pz0, c.slab(Blocks.STONE_BRICK_SLAB, SlabType.BOTTOM), ROOF);
            }
            c.banner(door - 1, 4, pz0 - 1, DyeColor.BLUE, c.front);
            c.banner(door + 2, 4, pz0 - 1, DyeColor.BLUE, c.front);
            // The garden: clipped hedges, paths, trees, benches and lamps.
            for (int x = door; x <= door + 1; x++) for (int z = c.zf; z < pz0 - 1; z++) c.put(x, -1, z, Blocks.GRAVEL, GROUND);
            c.hedge(1, door - 2, c.zf + 1);
            c.hedge(door + 3, c.W - 2, c.zf + 1);
            c.tree(1, hall.z1 + 2 < c.D ? hall.z1 + 2 : c.D - 1, 5);
            c.tree(c.W - 2, hall.z1 + 2 < c.D ? hall.z1 + 2 : c.D - 1, 5);
            c.lampPost(door - 2, pz0 - 2);
            c.lampPost(door + 3, pz0 - 2);
            c.bench(1, 2, pz0 - 2, c.right);
            c.bench(c.W - 3, c.W - 2, pz0 - 2, c.left);
            c.flowers(0, c.zf, c.W - 1, c.D - 1, 0.15f);
            c.scaffoldAround(hall);
            return "great_library";
        }

        /**
         * The Cathedral: a cruciform church of dressed stone. A tall nave with a slate roof crossed by a transept,
         * two west towers with belfries and spires, a spire over the crossing, a rose window over the great door,
         * lancet windows of stained glass, flying buttresses, and a paved close with trees and lamps.
         */
        static String cathedral(Canvas c) {
            BlockState[] m = masonry(c);
            BlockState stoneB = m[0], trim = m[1], plinth = m[2], column = m[3];
            BlockState floor = Blocks.POLISHED_ANDESITE.defaultBlockState();
            boolean desert = c.p.key().equals("desert");
            Block roofStair = desert ? Blocks.SMOOTH_SANDSTONE_STAIRS : Blocks.DEEPSLATE_TILE_STAIRS;
            Block roofSlab = desert ? Blocks.SMOOTH_SANDSTONE_SLAB : Blocks.DEEPSLATE_TILE_SLAB;
            Block buttressStair = desert ? Blocks.SANDSTONE_STAIRS : Blocks.STONE_BRICK_STAIRS;
            int z0 = c.zf + 6, mid = c.W / 2; // the nave's axis runs between mid - 1 and mid
            Box nave = new Box(mid - 4, z0, mid + 3, c.D - 3);
            Box transept = new Box(mid - 8, z0 + 7, mid + 7, z0 + 11);
            Box towerL = new Box(mid - 7, z0 - 2, mid - 4, z0 + 1), towerR = new Box(mid + 3, z0 - 2, mid + 6, z0 + 1);
            Wall stone = new Wall(trim, stoneB.getBlock(), stoneB, stoneB, plinth, null, false, true);
            for (Box b : new Box[] {nave, transept, towerL, towerR}) c.foundation(b, stoneB, floor);
            for (int y = 0; y <= 2; y++) { c.doorway(mid - 1, y, nave.z0); c.doorway(mid, y, nave.z0); }
            int top = c.walls(nave, 0, 3, stone, Blocks.AIR.defaultBlockState());
            c.walls(transept, 0, 3, stone, Blocks.AIR.defaultBlockState());
            int tt = c.walls(towerL, 0, 5, stone, floor);
            c.walls(towerR, 0, 5, stone, floor);
            // Open the crossing: no walls between the nave and the transept's arms below the vault.
            for (int y = 0; y < top - 1; y++) {
                for (int x = nave.x0 + 1; x < nave.x1; x++) { c.erase(x, y, transept.z0); c.erase(x, y, transept.z1); }
                for (int z = transept.z0 + 1; z < transept.z1; z++) { c.erase(nave.x0, y, z); c.erase(nave.x1, y, z); }
            }
            // A stone vault over nave and transept.
            for (Box b : new Box[] {nave, transept}) {
                for (int x = b.x0 + 1; x < b.x1; x++) for (int z = b.z0 + 1; z < b.z1; z++) c.put(x, top, z, stoneB, FRAME);
            }
            // Lancet windows of stained glass, three tiers high, along the nave and the transept's ends.
            DyeColor[] glass = {DyeColor.BLUE, DyeColor.RED, DyeColor.PURPLE, DyeColor.YELLOW, DyeColor.LIGHT_BLUE};
            for (int z = nave.z0 + 2; z < nave.z1 - 1; z += 2) {
                if (z >= transept.z0 - 1 && z <= transept.z1 + 1) continue;
                for (int x : new int[] {nave.x0, nave.x1}) for (int y = 1; y < top; y++) {
                    if (y % STOREY == 3) continue;
                    c.put(x, y, z, Blocks.STAINED_GLASS_PANE.pick(glass[Math.floorMod(z / 2 + y / STOREY, glass.length)]), FRAME);
                }
            }
            for (int x : new int[] {transept.x0, transept.x1}) for (int y = 1; y < top; y++) {
                if (y % STOREY != 3) c.put(x, y, transept.cz(), Blocks.STAINED_GLASS_PANE.pick(glass[y / STOREY % glass.length]), FRAME);
            }
            reglaze(c, new Box(0, c.zf, c.W - 1, c.D - 1), pos -> Blocks.STAINED_GLASS_PANE.pick(DyeColor.LIGHT_BLUE).defaultBlockState());
            // The rose window over the great door.
            for (int x = mid - 3; x <= mid + 2; x++) for (int y = 4; y <= 10; y++) {
                double dx = x - (mid - 0.5), dy = y - 7, d2 = dx * dx + dy * dy;
                if (d2 > 7.5) continue;
                DyeColor col = d2 < 1 ? DyeColor.YELLOW : d2 < 3.5 ? DyeColor.RED : DyeColor.BLUE;
                c.put(x, y, nave.z0, Blocks.STAINED_GLASS_PANE.pick(col), FRAME);
            }
            // Roofs: the transept first, so the nave's roof wins where they cross; a slender spire on the crossing.
            int tr = c.gable(transept, top + 1, true, roofStair, roofSlab, stoneB);
            int ridge = c.gable(nave, top + 1, false, roofStair, roofSlab, stoneB);
            int cx0 = mid - 1, cz0 = transept.cz();
            for (int y = Math.max(tr, top + 1); y <= ridge + 2; y++) for (int x = cx0; x <= cx0 + 1; x++) for (int z = cz0; z <= cz0 + 1; z++) {
                c.put(x, y, z, y == ridge + 2 ? trim : stoneB, ROOF);
            }
            int sp = c.hip(new Box(cx0, cz0, cx0 + 1, cz0 + 1), ridge + 3, roofStair, roofSlab);
            c.put(cx0, sp + 1, cz0, column, ROOF);
            c.put(cx0, sp + 2, cz0, c.lantern(false), FIT);
            // The towers: belfries with bells, then tall spires.
            for (Box t : new Box[] {towerL, towerR}) {
                for (int[] k : new int[][] {{t.x0, t.z0}, {t.x1, t.z0}, {t.x0, t.z1}, {t.x1, t.z1}}) {
                    for (int y = tt + 1; y <= tt + 3; y++) c.put(k[0], y, k[1], trim, FRAME);
                }
                for (int x = t.x0; x <= t.x1; x++) for (int z = t.z0; z <= t.z1; z++) c.put(x, tt + 4, z, stoneB, FRAME);
                c.masses.add(new int[] {t.x0, t.z0, t.x1, t.z1, tt + 4});
                c.put(t.cx(), tt + 3, t.cz(), Blocks.BELL.defaultBlockState().setValue(BellBlock.ATTACHMENT, BellAttachType.CEILING)
                        .setValue(BellBlock.FACING, c.front), FIT);
                int spire = c.hip(t, tt + 5, roofStair, roofSlab);
                c.put(t.cx(), spire + 1, t.cz(), column, ROOF);
                c.put(t.cx(), spire + 2, t.cz(), c.lantern(false), FIT);
                c.banner(t.cx(), 9, t.z0 - 1, DyeColor.PURPLE, c.front);
            }
            // The great door, under a pointed porch.
            c.door(mid - 1, nave.z0, c.back, DoorHingeSide.LEFT);
            c.door(mid, nave.z0, c.back, DoorHingeSide.RIGHT);
            for (int y = 0; y <= 3; y++) { c.put(mid - 2, y, nave.z0 - 1, column, FRAME); c.put(mid + 1, y, nave.z0 - 1, column, FRAME); }
            c.put(mid - 1, 3, nave.z0 - 1, c.stairs(buttressStair, c.right, true), FRAME);
            c.put(mid, 3, nave.z0 - 1, c.stairs(buttressStair, c.left, true), FRAME);
            c.put(mid - 2, 4, nave.z0 - 1, trim, FRAME);
            c.put(mid + 1, 4, nave.z0 - 1, trim, FRAME);
            // Flying buttresses along the nave.
            for (int z = nave.z0 + 3; z < nave.z1; z += 3) {
                if (z >= transept.z0 - 1 && z <= transept.z1 + 1) continue;
                for (int side = -1; side <= 1; side += 2) {
                    int wallX = side < 0 ? nave.x0 : nave.x1, px = wallX + 2 * side, ax = wallX + side;
                    if (!c.inLot(px, z)) continue;
                    for (int y = 0; y <= 5; y++) c.put(px, y, z, stoneB, FRAME);
                    c.put(px, 6, z, c.stairs(buttressStair, c.dir(-side, 0), false), FRAME);
                    c.put(ax, 6, z, stoneB, FRAME);
                    c.put(ax, 7, z, c.stairs(buttressStair, c.dir(-side, 0), false), FRAME);
                    c.put(ax, 5, z, c.stairs(buttressStair, c.dir(side, 0), true), FRAME);
                }
            }
            // Inside: a red carpet up the aisle, pews either side, chandeliers, the altar under the east window.
            for (int z = nave.z0 + 1; z < nave.z1 - 1; z++) for (int x = mid - 1; x <= mid; x++) c.put(x, 0, z, Blocks.CARPET.pick(DyeColor.RED), FIT);
            for (int z = nave.z0 + 2; z < nave.z1 - 2; z += 2) {
                if (z >= transept.z0 && z <= transept.z1) continue;
                for (int x = nave.x0 + 1; x <= mid - 2; x++) c.put(x, 0, z, c.stairs(Style.stairsOf(c.p), c.back, false), FIT);
                for (int x = mid + 1; x <= nave.x1 - 1; x++) c.put(x, 0, z, c.stairs(Style.stairsOf(c.p), c.back, false), FIT);
            }
            for (int z = nave.z0 + 3; z < nave.z1; z += 4) c.put(mid, top - 1, z, c.lantern(true), FIT);
            c.put(mid - 1, top - 1, transept.cz(), c.lantern(true), FIT);
            for (int x = mid - 1; x <= mid; x++) {
                c.put(x, 0, nave.z1 - 1, trim, FIT);
                c.put(x, 1, nave.z1 - 1, Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.CANDLES, 3), FIT);
            }
            // The close: paving before the west front, trees, lamps and benches round the church.
            for (int x = 0; x < c.W; x++) for (int z = c.zf; z < towerL.z0; z++) {
                c.put(x, -1, z, ((x + z) % 2 == 0 ? stoneB : floor), GROUND);
            }
            for (int[] k : new int[][] {{1, c.zf + 1}, {c.W - 2, c.zf + 1}}) c.tree(k[0], k[1], 5);
            c.lampPost(mid - 4, c.zf + 1);
            c.lampPost(mid + 3, c.zf + 1);
            c.bench(1, 2, c.D - 2, c.front);
            c.bench(c.W - 3, c.W - 2, c.D - 2, c.front);
            c.tree(2, c.D - 5, 6);
            c.tree(c.W - 3, c.D - 5, 6);
            c.flowers(0, towerL.z0, c.W - 1, c.D - 1, 0.12f);
            c.scaffoldAround(nave);
            return "cathedral";
        }

        /**
         * The Castle Keep: curtain walls with battlements and four corner towers, a gatehouse with a portcullis,
         * and in the courtyard a great square keep, flying the town's banner, with a smithy, stables and a
         * practice yard.
         */
        static String castleKeep(Canvas c) {
            BlockState[] m = masonry(c);
            BlockState stoneB = m[0], trim = m[1], plinth = m[2], merlon = m[3];
            BlockState cracked = c.p.key().equals("desert") ? stoneB : Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
            BlockState floor = c.p.floor().defaultBlockState();
            Wall stone = new Wall(trim, stoneB.getBlock(), stoneB, stoneB, plinth, null, false, false);
            int zw = c.zf + 2, mid = c.W / 2;
            Box ward = new Box(1, zw, c.W - 2, c.D - 2);
            Box gate = new Box(mid - 3, c.zf, mid + 2, zw + 2);
            // Curtain walls, six high, battlements on top (the gatehouse stands in the front one).
            for (int x = ward.x0; x <= ward.x1; x++) for (int z = ward.z0; z <= ward.z1; z++) {
                if (!ward.edge(x, z) || gate.contains(x, z)) continue;
                c.put(x, -1, z, plinth, GROUND);
                for (int y = 0; y <= 5; y++) c.put(x, y, z, (x * 7 + y * 3 + z * 5) % 11 == 0 ? cracked : y == 0 ? plinth : stoneB, FRAME);
                if ((x + z) % 2 == 0) c.put(x, 6, z, merlon, ROOF);
            }
            // Corner towers.
            Box[] towers = {new Box(0, zw - 1, 3, zw + 2), new Box(c.W - 4, zw - 1, c.W - 1, zw + 2),
                    new Box(0, c.D - 4, 3, c.D - 1), new Box(c.W - 4, c.D - 4, c.W - 1, c.D - 1)};
            for (Box t : towers) {
                c.foundation(t, plinth, floor);
                int top = c.walls(t, 0, 2, stone, floor);
                crenellate(c, t, top + 1, stoneB, merlon);
                c.put(t.cx(), top + 3, t.cz(), Style.fence(c.p), ROOF);
                c.put(t.cx(), top + 4, t.cz(), c.lantern(false), FIT);
                reglaze(c, t, pos -> Blocks.IRON_BARS.defaultBlockState());
            }
            // The gatehouse: a passage through, a portcullis over it, a banner either side.
            c.foundation(gate, plinth, stoneB);
            for (int y = 0; y <= 2; y++) for (int x = mid - 1; x <= mid; x++) { c.doorway(x, y, gate.z0); c.doorway(x, y, gate.z1); }
            int gt = c.walls(gate, 0, 2, stone, floor);
            crenellate(c, gate, gt + 1, stoneB, merlon);
            reglaze(c, gate, pos -> Blocks.IRON_BARS.defaultBlockState());
            for (int x = mid - 1; x <= mid; x++) {
                c.put(x, 2, gate.z0, Blocks.IRON_BARS, FIT);
                for (int z = gate.z0 + 1; z < gate.z1; z++) { c.erase(x, 0, z); c.erase(x, 1, z); c.erase(x, 2, z); c.put(x, -1, z, Blocks.GRAVEL, GROUND); }
            }
            c.banner(mid - 2, 5, gate.z0 - 1, DyeColor.RED, c.front);
            c.banner(mid + 1, 5, gate.z0 - 1, DyeColor.RED, c.front);
            c.put(mid - 3, 3, gate.z0 - 1, c.facing(Blocks.WALL_TORCH, c.front), FIT);
            c.put(mid + 2, 3, gate.z0 - 1, c.facing(Blocks.WALL_TORCH, c.front), FIT);
            // The keep: four storeys, battlements, turrets at the corners and a flag on a pole.
            Box keep = new Box(mid - 5, zw + 6, mid + 4, Math.min(c.D - 5, zw + 15));
            c.foundation(keep, plinth, floor);
            for (int y = 0; y <= 2; y++) { c.doorway(mid - 1, y, keep.z0); c.doorway(mid, y, keep.z0); }
            int kt = c.walls(keep, 0, 4, stone, floor);
            crenellate(c, keep, kt + 1, stoneB, merlon);
            for (int[] k : new int[][] {{keep.x0, keep.z0}, {keep.x1 - 1, keep.z0}, {keep.x0, keep.z1 - 1}, {keep.x1 - 1, keep.z1 - 1}}) {
                Box turret = new Box(k[0], k[1], k[0] + 1, k[1] + 1);
                for (int x = turret.x0; x <= turret.x1; x++) for (int z = turret.z0; z <= turret.z1; z++) {
                    for (int y = kt + 2; y <= kt + 4; y++) c.put(x, y, z, stoneB, ROOF);
                    c.put(x, kt + 5, z, merlon, ROOF);
                }
            }
            for (int y = kt + 2; y <= kt + 7; y++) c.put(keep.cx(), y, keep.cz(), Style.fence(c.p), ROOF);
            c.put(keep.cx(), kt + 8, keep.cz(), Blocks.BANNER.pick(DyeColor.RED), ROOF);
            c.door(mid - 1, keep.z0, c.back, DoorHingeSide.LEFT);
            c.door(mid, keep.z0, c.back, DoorHingeSide.RIGHT);
            for (int x = mid - 1; x <= mid; x++) c.put(x, -1, keep.z0 - 1, Blocks.POLISHED_ANDESITE, GROUND);
            c.banner(mid - 3, 6, keep.z0 - 1, DyeColor.RED, c.front);
            c.banner(mid + 2, 6, keep.z0 - 1, DyeColor.RED, c.front);
            // The great hall inside: a long table, a throne, banners.
            for (int z = keep.z0 + 2; z <= keep.z1 - 3; z++) c.put(keep.cx(), 0, z, c.p.floor().defaultBlockState(), FIT);
            c.put(keep.cx(), 0, keep.z1 - 1, c.stairs(Blocks.STONE_BRICK_STAIRS, c.front, false), FIT);
            c.put(keep.cx(), STOREY - 1, keep.cz(), c.lantern(true), FIT);
            // The ward: a gravel way from the gate to the keep, a smithy, stables and a practice yard.
            for (int z = gate.z1 + 1; z < keep.z0 - 1; z++) for (int x = mid - 1; x <= mid; x++) c.put(x, -1, z, Blocks.GRAVEL, GROUND);
            for (int x = ward.x0 + 1; x < ward.x1; x++) for (int z = ward.z0 + 1; z < ward.z1; z++) {
                if (!c.has(x, -1, z) && (x * 3 + z * 7) % 5 == 0) c.put(x, -1, z, Blocks.COARSE_DIRT, GROUND);
            }
            int sx = ward.x0 + 2, sz = gate.z1 + 2;
            c.put(sx, 0, sz, Blocks.ANVIL, FIT);
            c.put(sx + 1, 0, sz, Blocks.SMITHING_TABLE, FIT);
            c.put(sx + 2, 0, sz, Blocks.BLAST_FURNACE, FIT);
            c.put(sx, 0, sz + 1, Blocks.GRINDSTONE, FIT);
            for (int x = sx; x <= sx + 2; x++) c.put(x, 2, sz, c.slab(Blocks.STONE_BRICK_SLAB, SlabType.BOTTOM), ROOF);
            c.put(sx, 1, sz + 1, Style.fence(c.p), FRAME);
            c.put(sx + 2, 1, sz + 1, Style.fence(c.p), FRAME);
            for (int x = sx; x <= sx + 2; x++) c.put(x, 2, sz + 1, c.slab(Blocks.STONE_BRICK_SLAB, SlabType.BOTTOM), ROOF);
            int px = ward.x1 - 4, pz = gate.z1 + 2;
            for (int k = 0; k < 2; k++) {
                c.put(px + 2 * k, 0, pz, Blocks.HAY_BLOCK, FIT);
                c.put(px + 2 * k, 1, pz, Blocks.TARGET, FIT);
            }
            c.fenceRun(ward.x0 + 1, keep.z1 - 3, keep.x0 - 2, ward.z1 - 1, keep.x0 - 2, keep.z1 - 1);
            c.put(ward.x0 + 2, 0, ward.z1 - 2, Blocks.HAY_BLOCK, GARDEN);
            c.put(ward.x0 + 3, 0, ward.z1 - 2, Blocks.HAY_BLOCK, GARDEN);
            c.put(ward.x1 - 2, 0, ward.z1 - 2, Blocks.BARREL.defaultBlockState(), GARDEN);
            c.put(ward.x1 - 3, 0, ward.z1 - 2, Blocks.BARREL.defaultBlockState(), GARDEN);
            for (int x = ward.x0 + 4; x < ward.x1 - 3; x += 6) c.put(x, 6, ward.z0, c.lantern(false), FIT);
            c.scaffoldAround(keep);
            return "castle_keep";
        }
    }
}
