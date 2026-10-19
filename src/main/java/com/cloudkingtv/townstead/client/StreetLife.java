package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.city.CitySummary;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadRouter;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.road.RoadType;
import com.cloudkingtv.townstead.sim.TownModel;
import com.cloudkingtv.townstead.transit.CoachLine;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Variant;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.jspecify.annotations.Nullable;

/**
 * The town's streets come alive, client side only (no server entities, so it costs nothing on the server): horse
 * carts drive the roads, as many as each road's traffic says, and coaches run every coach line, stopping at each
 * stop. They're puppets: vanilla horses, minecarts and villagers that are never added to the world, moved along
 * the road graph each tick and drawn with the vanilla renderers.
 */
@EventBusSubscriber(modid = Townstead.MODID, value = Dist.CLIENT)
public final class StreetLife {
    /** Vehicles are kept within this many blocks of the camera. */
    private static final double RADIUS = 112;
    /** At most this many carts at once. */
    private static final int MAX_CARTS = 36;
    /** Trips a day behind each cart on a 64-block road. */
    private static final double TRIPS_PER_CART = 6;
    /** Ticks a coach waits at a stop. */
    private static final int DWELL = 50;
    private static final BlockState[] CARGO = {Blocks.HAY_BLOCK.defaultBlockState(), Blocks.BARREL.defaultBlockState(),
            Blocks.OAK_LOG.defaultBlockState(), Blocks.PUMPKIN.defaultBlockState(), Blocks.MELON.defaultBlockState(),
            Blocks.COMPOSTER.defaultBlockState(), Blocks.CHEST.defaultBlockState()};

    private static final List<Vehicle> CARTS = new ArrayList<>();
    private static final Map<Integer, List<Vehicle>> COACHES = new HashMap<>();
    private static final List<Drawn> DRAWN = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();
    /** Puppets need ids (renderers seed animations with them); negative ones never clash with real entities. */
    private static int nextId = -1_000_000;
    private static boolean warned;
    private static @Nullable Object modelKey, coachKey;
    private static @Nullable TownModel model;
    private static @Nullable ClientLevel lastLevel;

    private record Drawn(EntityRenderState state) {}

    /** A route as a run of legs, measured in blocks; a position along it is a point in the right-hand lane. */
    static final class Path {
        final List<RoadRouter.Leg> legs;
        final double[] start;
        final double total;
        final boolean loop;

        Path(List<RoadRouter.Leg> legs, RoadRouter router, boolean loop) {
            this.legs = legs;
            this.loop = loop;
            this.start = new double[legs.size()];
            double s = 0;
            for (int i = 0; i < legs.size(); i++) {
                start[i] = s;
                s += Math.max(0.01, Math.abs(legs.get(i).t1() - legs.get(i).t0()) * router.length(legs.get(i).segment()));
            }
            this.total = s;
        }

        /** {x, y, z, yaw} at distance s (wrapped on loops, clamped otherwise). */
        double @Nullable [] at(RoadNetwork net, double s) {
            if (legs.isEmpty() || total <= 0) return null;
            s = loop ? Math.floorMod((long) Math.floor(s * 1000), (long) Math.floor(total * 1000)) / 1000.0 : Mth.clamp(s, 0, total);
            int i = legs.size() - 1;
            for (int k = 0; k < legs.size(); k++) if (k + 1 == legs.size() || start[k + 1] > s) { i = k; break; }
            RoadRouter.Leg leg = legs.get(i);
            RoadSegment seg = net.segment(leg.segment());
            if (seg == null) return null;
            double len = (i + 1 < legs.size() ? start[i + 1] : total) - start[i];
            double f = len <= 0 ? 0 : (s - start[i]) / len;
            double t = leg.t0() + (leg.t1() - leg.t0()) * f;
            var d = seg.curve().direction(t);
            double sign = leg.t1() >= leg.t0() ? 1 : -1;
            double dx = d.x * sign, dz = d.y * sign, n = Math.max(1e-6, Math.hypot(dx, dz));
            dx /= n; dz /= n;
            double lane = lane(seg.type());
            double x = seg.curve().x(t) - dz * lane, z = seg.curve().z(t) + dx * lane;
            double y = height(seg, t) + 0.94;
            return new double[] {x, y, z, Math.toDegrees(Math.atan2(-dx, dz))};
        }
    }

    /** How far right of the centreline traffic drives. */
    static double lane(RoadType type) {
        return type == RoadType.AVENUE ? 2.0 : 0.8;
    }

    /** The graded surface height at t, smoothly (the stored heights are whole blocks). */
    static double height(RoadSegment s, double t) {
        int[] h = s.heights();
        if (h.length == 1) return h[0];
        double f = Mth.clamp(t, 0, 1) * (h.length - 1);
        int i = Math.min((int) f, h.length - 2);
        return Mth.lerp(f - i, h[i], h[i + 1]);
    }

    /** A cart (one animal, a cargo cart) or a coach (two horses, a coach with riders). */
    static final class Vehicle {
        final Path path;
        final AbstractHorse[] team;
        final Minecart cart;
        final Villager[] riders;
        final double speed;
        double s;
        int dwell;
        int nextStop;
        final double[] stops;

        Vehicle(Path path, AbstractHorse[] team, Minecart cart, Villager[] riders, double speed, double s, double[] stops) {
            this.path = path;
            this.team = team;
            this.cart = cart;
            this.riders = riders;
            this.speed = speed;
            this.s = s;
            this.stops = stops;
        }

        List<Entity> parts() {
            List<Entity> out = new ArrayList<>(List.of(team));
            out.add(cart);
            out.addAll(List.of(riders));
            return out;
        }

        /** Moves along; false once a cart has driven off the end of its path. */
        boolean tick(RoadNetwork net) {
            double before = s;
            if (dwell > 0) dwell--;
            else s += speed;
            if (stops.length > 0 && dwell == 0) {
                double at = stops[nextStop % stops.length];
                double lap = Math.floor(before / path.total) * path.total;
                if (before < lap + at && s >= lap + at || before < lap + at + path.total && s >= lap + at + path.total) {
                    s = Math.max(before, s - speed);
                    dwell = DWELL;
                    nextStop = (nextStop + 1) % stops.length;
                }
            }
            if (!path.loop && s >= path.total - 2.2) return false;
            double[] c = path.at(net, s), h = path.at(net, s + 2.1);
            if (c == null || h == null) return false;
            place(cart, c[0], c[1], c[2], (float) c[3] + 90f, false);
            double side = team.length > 1 ? 0.5 : 0;
            for (int i = 0; i < team.length; i++) {
                double off = team.length > 1 ? (i == 0 ? -side : side) : 0;
                double yaw = Math.toRadians(h[3]);
                place(team[i], h[0] + Math.cos(yaw) * off, h[1], h[2] + Math.sin(yaw) * off, (float) h[3], dwell == 0);
            }
            for (int i = 0; i < riders.length; i++) {
                double yaw = Math.toRadians(c[3]);
                double back = (i - (riders.length - 1) / 2.0) * 0.45;
                place(riders[i], c[0] - Math.sin(yaw) * -back, c[1] + 0.25, c[2] + Math.cos(yaw) * -back, (float) c[3], false);
            }
            return true;
        }
    }

    private static void place(Entity e, double x, double y, double z, float yaw, boolean walking) {
        boolean first = e.tickCount == 0;
        double moved = first ? 0 : Math.hypot(x - e.getX(), z - e.getZ());
        if (first) e.snapTo(x, y, z, yaw, 0);
        e.setOldPosAndRot();
        e.setPos(x, y, z);
        e.setYRot(yaw);
        e.tickCount++;
        if (e instanceof LivingEntity le) {
            le.yBodyRotO = first ? yaw : le.yBodyRot;
            le.yHeadRotO = first ? yaw : le.yHeadRot;
            le.yBodyRot = yaw;
            le.yHeadRot = yaw;
            le.walkAnimation.update(walking ? (float) Math.min(1, moved * 4) : 0, 0.4f, 1f);
        }
    }

    // ---------------------------------------------------------------- ticking

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != lastLevel) { clear(); lastLevel = level; }
        if (level == null || mc.isPaused()) return;
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        CitySummary city = cityAt(cam);
        if (city == null) { clear(); return; }
        TownModel m = model(city);
        RoadNetwork net = ClientRoads.network();
        RoadRouter router = m.traffic.router();
        coaches(level, city, m, router);
        CARTS.removeIf(v -> !v.tick(net) || far(v, cam));
        for (List<Vehicle> list : COACHES.values()) for (Vehicle v : list) v.tick(net);
        spawnCarts(level, m, router, net, cam);
    }

    private static boolean far(Vehicle v, Vec3 cam) {
        return Math.hypot(v.cart.getX() - cam.x, v.cart.getZ() - cam.z) > RADIUS + 16;
    }

    /** The town whose land the camera is over (or near). */
    private static @Nullable CitySummary cityAt(Vec3 cam) {
        for (CitySummary c : ClientCityCache.all()) {
            if (c.contains((int) Math.floor(cam.x), (int) Math.floor(cam.z))) return c;
        }
        return null;
    }

    private static TownModel model(CitySummary city) {
        Object key = List.of(ClientLand.version(), ClientRoads.version(), city.id(), city.lines(), city.budget().budgets.clone().length,
                java.util.Arrays.hashCode(city.budget().budgets), city.treasury() < 0);
        if (model == null || !key.equals(modelKey)) {
            modelKey = key;
            model = TownModel.of(city.id(), ClientLand.get().buildingsOf(city.id()), ClientRoads.network(), city.budget(), city.treasury(),
                    city.tiles(), city.townHall(), city.lines());
        }
        return model;
    }

    /** One coach per coach the line runs, spread evenly round it; rebuilt when the lines change. */
    private static void coaches(ClientLevel level, CitySummary city, TownModel m, RoadRouter router) {
        Object key = List.of(modelKey == null ? 0 : modelKey);
        if (key.equals(coachKey)) return;
        coachKey = key;
        COACHES.clear();
        for (CoachLine line : city.lines()) {
            var stats = m.traffic.line(line.id());
            if (stats == null || stats.coaches() <= 0 || stats.route().isEmpty()) continue;
            Path path = new Path(stats.route(), router, true);
            // Where each stop falls along the loop: the route is built stop to stop, so measure each part.
            double[] stops = new double[line.stops().size()];
            double run = 0;
            for (int i = 0; i < line.stops().size(); i++) {
                stops[i] = run;
                RoadRouter.Access a = line.access(router, i), b = line.access(router, (i + 1) % line.stops().size());
                var part = a == null || b == null ? null : router.route(a, b, router::freeTime);
                if (part != null) run += router.length(part);
            }
            List<Vehicle> list = new ArrayList<>();
            for (int i = 0; i < stats.coaches(); i++) {
                Minecart cart = puppet(new Minecart(EntityTypes.MINECART, level));
                cart.setCustomDisplayBlockState(Optional.of(net.minecraft.world.level.block.Blocks.CARPET.pick(line.color()).defaultBlockState()));
                cart.setDisplayOffset(22);
                AbstractHorse[] team = {horse(level, EntityTypes.HORSE, i), horse(level, EntityTypes.HORSE, i + 3)};
                Villager[] riders = {villager(level, i), villager(level, i + 7)};
                Vehicle v = new Vehicle(path, team, cart, riders, 0.22, path.total * i / stats.coaches(), stops);
                v.nextStop = firstStopAfter(stops, v.s);
                list.add(v);
            }
            COACHES.put(line.id(), list);
        }
    }

    private static int firstStopAfter(double[] stops, double s) {
        for (int i = 0; i < stops.length; i++) if (stops[i] > s) return i;
        return 0;
    }

    /** Keeps the cart count near what the roads in view carry, each starting on a road picked by its traffic. */
    private static void spawnCarts(ClientLevel level, TownModel m, RoadRouter router, RoadNetwork net, Vec3 cam) {
        List<RoadSegment> near = new ArrayList<>();
        double want = 0, weight = 0;
        for (int id : router.segmentIds()) {
            RoadSegment s = net.segment(id);
            if (s == null) continue;
            double mx = s.curve().x(0.5), mz = s.curve().z(0.5);
            if (Math.hypot(mx - cam.x, mz - cam.z) > RADIUS) continue;
            near.add(s);
            double flow = Math.min(m.traffic.flow(id), 1.2 * com.cloudkingtv.townstead.sim.Traffic.capacity(s.type()));
            want += flow / TRIPS_PER_CART * router.length(id) / 64;
            weight += m.traffic.flow(id);
        }
        int target = Math.min(MAX_CARTS, Math.max(want > 0 ? 1 : 0, (int) Math.round(want)));
        if (CARTS.size() >= target || near.isEmpty() || weight <= 0 || RANDOM.nextInt(4) != 0) return;
        // Pick a road by its flow, a place and a direction on it, then wander the graph from there.
        double pick = RANDOM.nextDouble() * weight;
        RoadSegment start = near.getLast();
        for (RoadSegment s : near) { pick -= m.traffic.flow(s.id()); if (pick <= 0) { start = s; break; } }
        boolean forward = RANDOM.nextBoolean();
        double t = RANDOM.nextDouble();
        List<RoadRouter.Leg> legs = new ArrayList<>();
        legs.add(new RoadRouter.Leg(start.id(), t, forward ? 1 : 0));
        int node = forward ? start.b() : start.a(), from = start.id();
        for (int i = 0; i < 6; i++) {
            RoadSegment next = nextRoad(m, net, router, node, from);
            if (next == null) break;
            boolean fwd = next.a() == node;
            legs.add(new RoadRouter.Leg(next.id(), fwd ? 0 : 1, fwd ? 1 : 0));
            node = fwd ? next.b() : next.a();
            from = next.id();
        }
        Path path = new Path(legs, router, false);
        if (path.total < 6) return;
        int seed = RANDOM.nextInt(1000);
        EntityType<? extends AbstractHorse> kind = seed % 5 == 0 ? EntityTypes.DONKEY : seed % 7 == 0 ? EntityTypes.MULE : EntityTypes.HORSE;
        Minecart cart = puppet(new Minecart(EntityTypes.MINECART, level));
        cart.setCustomDisplayBlockState(Optional.of(CARGO[seed % CARGO.length]));
        Villager[] driver = seed % 3 == 0 ? new Villager[] {villager(level, seed)} : new Villager[0];
        CARTS.add(new Vehicle(path, new AbstractHorse[] {horse(level, kind, seed)}, cart, driver, 0.16 + RANDOM.nextDouble() * 0.06, 0, new double[0]));
    }

    /** The next road from a junction, weighted by traffic; back the way it came only at a dead end. */
    private static @Nullable RoadSegment nextRoad(TownModel m, RoadNetwork net, RoadRouter router, int node, int from) {
        List<RoadSegment> options = new ArrayList<>();
        double total = 0;
        for (RoadSegment s : net.segmentsAt(node)) {
            if (s.id() == from || !router.segmentIds().contains(s.id())) continue;
            options.add(s);
            total += 1 + m.traffic.flow(s.id());
        }
        if (options.isEmpty()) return net.segment(from);
        double pick = RANDOM.nextDouble() * total;
        for (RoadSegment s : options) { pick -= 1 + m.traffic.flow(s.id()); if (pick <= 0) return s; }
        return options.getLast();
    }

    private static <E extends Entity> E puppet(E e) {
        e.setId(nextId--);
        return e;
    }

    private static AbstractHorse horse(ClientLevel level, EntityType<? extends AbstractHorse> type, int seed) {
        AbstractHorse h = puppet(type.create(level, net.minecraft.world.entity.EntitySpawnReason.LOAD));
        if (h instanceof net.minecraft.world.entity.animal.equine.Horse horse) {
            horse.setComponent(net.minecraft.core.component.DataComponents.HORSE_VARIANT, Variant.values()[Math.floorMod(seed, Variant.values().length)]);
        }
        return h;
    }

    private static Villager villager(ClientLevel level, int seed) {
        Villager v = puppet(new Villager(EntityTypes.VILLAGER, level));
        var types = List.of(VillagerType.PLAINS, VillagerType.TAIGA, VillagerType.SAVANNA);
        var jobs = List.of(VillagerProfession.FARMER, VillagerProfession.LIBRARIAN, VillagerProfession.CLERIC, VillagerProfession.FLETCHER,
                VillagerProfession.SHEPHERD, VillagerProfession.NONE, VillagerProfession.MASON, VillagerProfession.CARTOGRAPHER);
        var reg = level.registryAccess();
        v.setVillagerData(v.getVillagerData().withType(reg, types.get(Math.floorMod(seed, types.size())))
                .withProfession(reg, jobs.get(Math.floorMod(seed / 3, jobs.size()))));
        return v;
    }

    private static void clear() {
        CARTS.clear();
        COACHES.clear();
        DRAWN.clear();
        coachKey = null;
        model = null;
        modelKey = null;
    }

    // ---------------------------------------------------------------- drawing

    @SubscribeEvent
    static void extract(ExtractLevelRenderStateEvent event) {
        DRAWN.clear();
        if (CARTS.isEmpty() && COACHES.isEmpty()) return;
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        float partial = event.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().position();
        List<Vehicle> all = new ArrayList<>(CARTS);
        for (List<Vehicle> l : COACHES.values()) all.addAll(l);
        for (Vehicle v : all) {
            if (v.cart.tickCount == 0 || Math.hypot(v.cart.getX() - cam.x, v.cart.getZ() - cam.z) > RADIUS + 8) continue;
            try {
                for (Entity e : v.parts()) DRAWN.add(new Drawn(dispatcher.extractEntity(e, partial)));
            } catch (RuntimeException ex) {
                // Street life is decoration: never let it take the game down.
                if (!warned) { Townstead.LOGGER.warn("Couldn't draw a street vehicle", ex); warned = true; }
                CARTS.remove(v);
            }
        }
    }

    @SubscribeEvent
    static void submit(SubmitCustomGeometryEvent event) {
        if (DRAWN.isEmpty()) return;
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        PoseStack pose = event.getPoseStack();
        Vec3 cam = camera.pos;
        for (Drawn d : DRAWN) {
            EntityRenderState s = d.state();
            dispatcher.submit(s, camera, s.x - cam.x, s.y - cam.y, s.z - cam.z, pose, event.getSubmitNodeCollector());
        }
    }

    private StreetLife() {}
}
