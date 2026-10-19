package com.cloudkingtv.townstead.life;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.BuildingGenerator;
import com.cloudkingtv.townstead.building.Residents;
import com.cloudkingtv.townstead.building.TownGrowth;
import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.economy.TownEconomy;
import com.cloudkingtv.townstead.registry.TownsteadRegistry;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.road.RoadType;
import com.cloudkingtv.townstead.sim.Happiness;
import com.cloudkingtv.townstead.sim.TownModel;
import com.cloudkingtv.townstead.zone.LandData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Keeps a sample of each town's residents out on its streets around every player in it: more in a bigger town,
 * fewer at night, none beyond {@link #REACH} blocks of a player. Each citizen is a real person from a real home near
 * where they appear, dressed for their trade.
 */
@EventBusSubscriber(modid = Townstead.MODID)
public final class Citizens {
    /** Citizens live within this many blocks of a player. */
    static final double REACH = 56;
    /** At most this many around one player. */
    static final int MAX_NEAR = 24;
    /** The villager professions citizens dress in, by index (the look's low bits). */
    static final List<String> OUTFITS = List.of("none", "farmer", "librarian", "cleric", "cartographer", "fletcher", "mason",
            "toolsmith", "weaponsmith", "armorer", "butcher", "leatherworker", "shepherd", "fisherman", "nitwit");
    /** The villager types for the town styles, by index (the look's high bits). */
    static final List<String> TYPES = List.of("plains", "desert", "savanna", "taiga", "snow");

    @SubscribeEvent
    static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % 20 != 7) return;
        CityManager cities = CityManager.get(level.getServer());
        Set<CitizenNpc> keep = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            City city = cities.at(level, player.blockPosition()).orElse(null);
            if (city == null) continue;
            List<CitizenNpc> near = level.getEntitiesOfClass(CitizenNpc.class, new AABB(player.blockPosition()).inflate(REACH));
            keep.addAll(near);
            int target = target(level, city);
            if (near.size() < target) spawn(level, city, player, near);
            else if (near.size() > target + 2) {
                // Evening: people go home (out of sight first).
                for (CitizenNpc c : near) {
                    if (c.distanceTo(player) > 20) { c.discard(); break; }
                }
            }
        }
        if (level.getGameTime() % 100 == 7) {
            for (CitizenNpc c : level.getEntities(TownsteadRegistry.CITIZEN.get(), c -> true)) {
                if (!keep.contains(c)) c.discard();
            }
        }
    }

    /** How many of the town's people are out near one player: a share of the population, fewer at night. */
    static int target(ServerLevel level, City city) {
        int people = 0;
        for (Building b : LandData.get(level).buildingsOf(city.id())) if (b.working()) people += Residents.people(b);
        boolean night = level.isDarkOutside();
        int n = Math.min(MAX_NEAR, people / 4);
        return night ? n / 4 : n;
    }

    private static void spawn(ServerLevel level, City city, ServerPlayer player, List<CitizenNpc> near) {
        RandomSource random = level.getRandom();
        Vec3 at = streetPoint(level, city.id(), player.position(), REACH - 8, random);
        if (at == null || at.distanceTo(player.position()) < 12) return;
        // Someone who lives near here and isn't out already.
        Set<String> out = new HashSet<>();
        for (CitizenNpc c : near) out.add(c.home + ":" + c.person);
        List<Building> homes = new ArrayList<>();
        for (Building b : LandData.get(level).buildingsOf(city.id())) {
            if (b.working() && b.zone.residential() && Math.hypot(b.centerX() - at.x, b.centerZ() - at.z) < 48) homes.add(b);
        }
        if (homes.isEmpty()) return;
        Building home = homes.get(random.nextInt(homes.size()));
        List<Residents.Person> people = new ArrayList<>();
        for (Residents.Household h : Residents.households(home)) people.addAll(h.members());
        people.removeIf(p -> out.contains(home.id + ":" + p.name()));
        if (people.isEmpty()) return;
        Residents.Person person = people.get(random.nextInt(people.size()));
        CitizenNpc npc = TownsteadRegistry.CITIZEN.get().create(level, EntitySpawnReason.EVENT);
        if (npc == null) return;
        npc.city = city.id();
        npc.home = home.id;
        npc.person = person.name();
        npc.setCustomName(Component.literal(person.name()));
        if (person.stage() == Residents.Stage.CHILD) npc.setAge(-24000);
        TownModel model = TownEconomy.model(level, city);
        Residents.Job job = model.jobsByPerson.get(home.id + ":" + person.name());
        npc.setLook(TYPES.indexOf(type(home.style)) * 32 + OUTFITS.indexOf(outfit(job == null ? null : job.trade(), person)));
        npc.snapTo(at.x, at.y, at.z, random.nextFloat() * 360, 0);
        level.addFreshEntity(npc);
    }

    static String type(String style) {
        return switch (style) {
            case "desert" -> "desert";
            case "savanna" -> "savanna";
            case "taiga" -> "taiga";
            case "snowy" -> "snow";
            default -> "plains";
        };
    }

    /** The villager outfit for a trade (and plain clothes for children, the retired and those between jobs). */
    static String outfit(@Nullable String trade, Residents.Person person) {
        if (trade == null) return person.stage() == Residents.Stage.SENIOR ? "nitwit" : "none";
        return switch (trade) {
            case "farmer", "gardener", "miller" -> "farmer";
            case "librarian", "scribe", "clerk", "teacher" -> "librarian";
            case "cleric", "healer", "keeper" -> "cleric";
            case "cartographer", "merchant" -> "cartographer";
            case "fletcher", "stallholder", "shop_hand" -> "fletcher";
            case "mason", "labourer" -> "mason";
            case "toolsmith", "smith" -> "toolsmith";
            case "weaponsmith", "guard", "firewatch" -> "weaponsmith";
            case "armorer" -> "armorer";
            case "butcher" -> "butcher";
            case "weaver", "shopkeeper" -> "shepherd";
            case "ostler", "coachman" -> "leatherworker";
            case "water_keeper" -> "fisherman";
            default -> "none";
        };
    }

    /**
     * A random place to walk on the town's streets near {@code from}: a sidewalk where the road has one, the verge
     * of a lane otherwise. Null if there's no town road nearby.
     */
    static @Nullable Vec3 streetPoint(ServerLevel level, UUID city, Vec3 from, double radius, RandomSource random) {
        List<RoadSegment> near = new ArrayList<>();
        for (RoadSegment s : RoadNetwork.get(level).segments()) {
            if (!s.city().equals(city)) continue;
            double t = s.curve().closestT(from.x, from.z);
            if (s.curve().distance(t, from.x, from.z) < radius) near.add(s);
        }
        for (int tries = 0; tries < 6 && !near.isEmpty(); tries++) {
            RoadSegment s = near.get(random.nextInt(near.size()));
            double t = random.nextDouble();
            var d = s.curve().direction(t).normalized();
            double side = random.nextBoolean() ? 1 : -1;
            double off = s.type() == RoadType.LANE ? s.type().halfWidth() + 0.5 : s.type().halfWidth() - 0.5;
            double x = s.curve().x(t) - d.y * off * side, z = s.curve().z(t) + d.x * off * side;
            if (Math.hypot(x - from.x, z - from.z) > radius) continue;
            int bx = Mth.floor(x), bz = Mth.floor(z);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            BlockPos p = new BlockPos(bx, y, bz);
            if (!level.isEmptyBlock(p) || !level.isEmptyBlock(p.above())) continue;
            if (!level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) continue;
            return new Vec3(bx + 0.5, y, bz + 0.5);
        }
        return null;
    }

    /** What a citizen says when you talk to them: who they are, home, work, mood and what's on their mind. */
    static void describe(ServerPlayer player, CitizenNpc npc) {
        ServerLevel level = player.level();
        City city = npc.city == null ? null : CityManager.get(level.getServer()).byId(npc.city).orElse(null);
        Building home = LandData.get(level).building(npc.home);
        if (city == null || home == null) {
            player.sendSystemMessage(Component.translatable("citizen.townstead.visitor", npc.getName()));
            return;
        }
        Residents.Person person = null;
        for (Residents.Household h : Residents.households(home)) for (Residents.Person p : h.members()) if (p.name().equals(npc.person)) person = p;
        if (person == null) return;
        TownModel model = TownEconomy.model(level, city);
        Component homeName = TownGrowth.kindName(BuildingGenerator.plan(home));
        player.sendSystemMessage(Component.translatable("citizen.townstead.hello", Component.literal(person.name()).withStyle(ChatFormatting.GOLD),
                person.age(), homeName));
        Residents.Job job = model.jobsByPerson.get(home.id + ":" + person.name());
        Component work;
        if (job != null) {
            Building w = LandData.get(level).building(job.workplaceId());
            Component place = w == null ? Component.empty() : TownGrowth.kindName(BuildingGenerator.plan(w));
            work = Component.translatable("citizen.townstead.works", Component.translatable("job.townstead." + job.trade()), place);
        } else {
            work = Component.translatable(switch (person.stage()) {
                case CHILD, TEEN -> model.schooled.contains(home.id) ? "citizen.townstead.school" : "citizen.townstead.no_school";
                case SENIOR -> "citizen.townstead.retired";
                case ADULT -> "citizen.townstead.looking";
            });
        }
        player.sendSystemMessage(work.copy().withStyle(ChatFormatting.GRAY));
        Happiness mood = model.happiness(home);
        Happiness.Factor worst = mood.worst(), best = null;
        for (Happiness.Factor f : mood.factors()) if (f.delta() > 0 && (best == null || f.delta() > best.delta())) best = f;
        Component thought = worst != null && worst.delta() <= -4
                ? Component.translatable("citizen.townstead.complaint", worst.label())
                : best != null ? Component.translatable("citizen.townstead.praise", best.label()) : Component.translatable("citizen.townstead.content");
        String moodKey = mood.total() >= 65 ? "happy" : mood.total() >= 40 ? "content" : "unhappy";
        player.sendSystemMessage(Component.translatable("citizen.townstead.mood", Component.translatable("citizen.townstead.mood." + moodKey),
                mood.total(), thought).withStyle(mood.total() >= 40 ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private Citizens() {}
}
