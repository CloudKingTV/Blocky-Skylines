package com.cloudkingtv.townstead.disaster;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.BuildingGenerator;
import com.cloudkingtv.townstead.building.Progression;
import com.cloudkingtv.townstead.building.TownGrowth;
import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.district.Policy;
import com.cloudkingtv.townstead.district.Specialization;
import com.cloudkingtv.townstead.progress.Chirps;
import com.cloudkingtv.townstead.progress.Milestone;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.service.ServiceType;
import com.cloudkingtv.townstead.sim.TownModel;
import com.cloudkingtv.townstead.zone.LandData;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Fires. Now and then a building catches fire (workshops, sawmills and taverns more often, a covered building far
 * less). Inside a fire station's reach the brigade comes and puts it out; outside it, the building burns down to a
 * charred shell, and the fire can jump to its neighbours. A burned-out building stands empty until the area is doing
 * well enough for people to rebuild it (with materials from the storehouse, like any repair).
 *
 * <p>The fire is drawn with smoke and flames, not real fire blocks, so it never spreads beyond the town's own
 * buildings or into the countryside.
 */
@EventBusSubscriber(modid = Townstead.MODID)
public final class Fires {
    /** A building outside any fire station's reach catches fire about once in this many ticks. */
    static final double MEAN_TICKS = 5_000_000;
    /** How long an unattended fire burns before the building is lost. */
    public static final int BURN_DOWN = 2400;
    /** How long the brigade takes to put a fire out at full budget. */
    public static final int PUT_OUT = 900;
    /** Fires jump to buildings whose centres are closer than this. */
    static final double SPREAD_REACH = 13;

    /**
     * One review of a town's fires ({@code dt} ticks since the last): new outbreaks, the brigade at work, buildings
     * lost and fires spreading.
     */
    public static void review(ServerLevel level, City city, List<Building> mine, TownModel model, double dt) {
        if (!TownsteadConfig.FIRES.get() || !Milestone.unlocked(city.milestone(), ServiceType.FIRE_STATION)) {
            for (Building b : mine) if (b.fire > 0) { b.fire = 0; dirty(level); }
            return;
        }
        RandomSource random = level.getRandom();
        for (Building b : mine) {
            if (b.fire > 0) {
                if (b.state != Building.State.ACTIVE) { b.fire = 0; dirty(level); continue; }
                b.fire += (int) Math.round(dt);
                dirty(level);
                boolean covered = model.covered(b, ServiceType.FIRE_STATION);
                if (covered && b.fire >= PUT_OUT / Math.max(0.25, model.strength(ServiceType.FIRE_STATION))) putOut(level, city, b);
                else if (!covered && b.fire >= BURN_DOWN) burnDown(level, city, b);
                else if (random.nextDouble() < (covered ? 0.02 : 0.07) * dt / 100) spread(level, city, b, mine, model);
            } else if (canBurn(b) && random.nextDouble() < dt / MEAN_TICKS * risk(b, model) * com.cloudkingtv.townstead.Difficulty.current().fires) {
                ignite(level, city, b);
            }
        }
    }

    /** How much likelier than an ordinary house this building is to catch fire. */
    public static double risk(Building b, TownModel model) {
        double r = switch (b.zone.need) {
            case WORKSHOPS -> 2.0;
            case SHOPS -> 1.2;
            default -> 1.0;
        };
        if (Specialization.FORESTRY.key().equals(b.spec)) r *= 1.5;
        if (Specialization.LEISURE.key().equals(b.spec)) r *= 1.4;
        if (b.level >= 3) r *= 1.25;
        if (model.civics.has(b, Policy.CLEAN_HEARTHS)) r *= 0.7;
        if (model.covered(b, ServiceType.FIRE_STATION)) r *= 0.25;
        return r;
    }

    static boolean canBurn(Building b) {
        return b.state == Building.State.ACTIVE && !b.civic() && b.fire == 0;
    }

    /** Sets a building alight. False if it can't burn (civic, unfinished, already burning). */
    public static boolean ignite(ServerLevel level, City city, Building b) {
        if (!canBurn(b)) return false;
        b.fire = 1;
        dirty(level);
        BlockPos at = center(b);
        level.playSound(null, at, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.0f, 1.0f);
        Component kind = TownGrowth.kindName(TownGrowth.plan(b));
        tell(level, city, Component.translatable("fire.townstead.started", kind), false);
        Chirps.post(level, city, "fire", at, true, kind);
        return true;
    }

    private static void spread(ServerLevel level, City city, Building from, List<Building> mine, TownModel model) {
        Building next = null;
        double best = SPREAD_REACH;
        for (Building o : mine) {
            if (o == from || !canBurn(o)) continue;
            double d = o.distanceTo(from);
            if (d < best) { best = d; next = o; }
        }
        if (next != null) ignite(level, city, next);
    }

    private static void putOut(ServerLevel level, City city, Building b) {
        b.fire = 0;
        dirty(level);
        BlockPos at = center(b);
        BuildingGenerator.Plan plan = TownGrowth.plan(b);
        level.playSound(null, at, SoundEvents.GENERIC_EXTINGUISH_FIRE, SoundSource.BLOCKS, 1.5f, 1.0f);
        level.sendParticles(ParticleTypes.CLOUD, true, false, at.getX() + 0.5, plan.max().getY() + 1, at.getZ() + 0.5, 40, 2.5, 1.5, 2.5, 0.05);
        level.sendParticles(ParticleTypes.SPLASH, at.getX() + 0.5, plan.max().getY(), at.getZ() + 0.5, 80, 2.5, 1.0, 2.5, 0.2);
        Component kind = TownGrowth.kindName(plan);
        tell(level, city, Component.translatable("fire.townstead.out", kind), true);
        Chirps.post(level, city, "fire_out", at, true, kind);
    }

    /**
     * The building is lost: roof and timber gone, the posts charred black, glass and furnishings burned away, embers
     * smouldering on the floor. Stone and brick still stand. It's abandoned, and rebuilt like any abandoned building
     * once things are well around it.
     */
    private static void burnDown(ServerLevel level, City city, Building b) {
        BuildingGenerator.Plan plan = TownGrowth.plan(b);
        RandomSource random = level.getRandom();
        // No neighbour updates while we char it: nothing pops off as items, and torches don't fall as loot.
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        int embers = 0;
        for (BuildingGenerator.Step s : plan.steps()) {
            BlockPos p = s.pos();
            BlockState here = level.getBlockState(p);
            if (here.isAir()) continue;
            BlockState now = charred(here, p.getY() - b.baseY, random);
            if (now == here) continue;
            if (here.getBlock() instanceof DoorBlock) level.setBlock(p.above(), Blocks.AIR.defaultBlockState(), flags);
            level.setBlock(p, now, flags);
        }
        // Embers on the ground floor.
        for (BuildingGenerator.Step s : plan.steps()) {
            if (embers >= 3) break;
            BlockPos p = s.pos();
            if (p.getY() != b.baseY + 1 || random.nextInt(6) != 0) continue;
            if (!level.getBlockState(p).isAir() || !level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
            level.setBlock(p, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, embers == 0), flags);
            embers++;
        }
        b.fire = 0;
        b.burned = true;
        b.state = Building.State.ABANDONED;
        b.trouble = Progression.ABANDON_AFTER;
        dirty(level);
        BlockPos at = center(b);
        level.playSound(null, at, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.8f, 0.5f);
        level.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, at.getX() + 0.5, b.baseY + 3, at.getZ() + 0.5, 120, 3, 2, 3, 0.05);
        Component kind = TownGrowth.kindName(plan);
        tell(level, city, Component.translatable("fire.townstead.burned", kind), false);
        Chirps.post(level, city, "burned", at, true, kind);
    }

    /** What a block becomes in a fire, {@code dy} blocks above the building's base. */
    static BlockState charred(BlockState state, int dy, RandomSource random) {
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (name.contains("glass") || name.contains("torch") || name.contains("lantern") || name.contains("carpet")
                || name.contains("pot") || name.contains("bed") || name.contains("banner") || name.contains("door")
                || name.contains("leaves") || name.contains("cobweb") || state.is(BlockTags.FLOWERS)) {
            return Blocks.AIR.defaultBlockState();
        }
        // The roof falls in once the timber under it has burned, whatever it was tiled with.
        boolean roofing = name.contains("stairs") || name.contains("slab");
        if (roofing && dy >= 4) return random.nextInt(5) == 0 ? state : Blocks.AIR.defaultBlockState();
        if (!state.ignitedByLava()) return state; // stone, brick, earth
        if (state.is(BlockTags.LOGS)) return dy <= 4 || random.nextInt(3) > 0 ? Blocks.BASALT.defaultBlockState() : Blocks.AIR.defaultBlockState();
        // Timber walls: the lower storey half stands, blackened; everything above (roof, upper floors) falls in.
        if (dy <= 2 && state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            return switch (random.nextInt(4)) {
                case 0 -> Blocks.BLACKSTONE.defaultBlockState();
                case 1 -> Blocks.COAL_BLOCK.defaultBlockState();
                default -> Blocks.AIR.defaultBlockState();
            };
        }
        return Blocks.AIR.defaultBlockState();
    }

    @SubscribeEvent
    static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 5 != 2) return;
        RandomSource random = level.getRandom();
        for (Building b : LandData.get(level).buildings()) {
            if (b.fire <= 0) continue;
            BlockPos at = center(b);
            if (!level.isLoaded(at)) continue;
            BuildingGenerator.Plan plan = TownGrowth.plan(b);
            List<BuildingGenerator.Step> steps = plan.steps();
            // Smoke pouring off the roof (seen from far off in the planner), flames licking out of the walls.
            for (int i = 0; i < 3; i++) {
                level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, true, at.getX() + 0.5 + random.nextGaussian() * 1.5, plan.max().getY() + 0.5,
                        at.getZ() + 0.5 + random.nextGaussian() * 1.5, 0, 0, 0.1, 0, 1);
            }
            level.sendParticles(ParticleTypes.LARGE_SMOKE, true, true, at.getX() + 0.5, plan.max().getY(), at.getZ() + 0.5, 10, 2, 0.5, 2, 0.03);
            for (int i = 0; i < 6 && !steps.isEmpty(); i++) {
                BlockPos p = steps.get(random.nextInt(steps.size())).pos();
                Direction side = Direction.getRandom(random);
                if (!level.getBlockState(p.relative(side)).isAir() || level.getBlockState(p).isAir()) continue;
                BlockPos f = p.relative(side);
                level.sendParticles(ParticleTypes.FLAME, f.getX() + 0.5, f.getY() + 0.3, f.getZ() + 0.5, 4, 0.3, 0.3, 0.3, 0.01);
                if (random.nextInt(3) == 0) level.sendParticles(ParticleTypes.LAVA, f.getX() + 0.5, f.getY() + 0.5, f.getZ() + 0.5, 1, 0.2, 0.2, 0.2, 0);
            }
            if (level.getGameTime() % 20 == 2) level.playSound(null, at, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 2.0f, 0.6f + random.nextFloat() * 0.3f);
        }
    }

    /** The nearest building of the town to {@code pos} that can catch fire, or null. */
    public static @Nullable Building nearest(ServerLevel level, City city, BlockPos pos) {
        Building best = null;
        double bestD = Double.MAX_VALUE;
        for (Building b : LandData.get(level).buildingsOf(city.id())) {
            if (!canBurn(b)) continue;
            double d = Math.hypot(b.centerX() - pos.getX(), b.centerZ() - pos.getZ());
            if (d < bestD) { bestD = d; best = b; }
        }
        return best;
    }

    private static BlockPos center(Building b) {
        return BlockPos.containing(b.centerX(), b.baseY + 1, b.centerZ());
    }

    private static void dirty(ServerLevel level) {
        LandData.get(level).setDirty();
        TownGrowth.markDirty();
    }

    private static void tell(ServerLevel level, City city, Component message, boolean ok) {
        for (ServerPlayer mayor : city.mayorsOnline(level.getServer())) RoadEditor.notify(mayor, message, ok);
    }

    private Fires() {}
}
