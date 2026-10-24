package com.cloudkingtv.townstead;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Where the server's time goes, town by town, and each town's share of a tick. Work is timed in sections that may
 * nest (working out the town's model inside a review): each section is charged only its own time, the nested
 * section's being charged to it. Times collect over a window of {@link #WINDOW} ticks; {@code /townstead perf}
 * reads the last full one.
 *
 * <p>The tick budget: a town that has spent its {@link TownsteadConfig#TOWN_TICK_BUDGET} this tick leaves the work
 * that can wait (another block on a site, finding new lots, the round of reviews) for the next.
 */
@EventBusSubscriber(modid = Townstead.MODID)
public final class TownProfiler {
    public enum Section { BUILD, GROW, REVIEW, CATCH_UP, MODEL, ECONOMY, FIRES, CITIZENS, SYNC }

    /** Ticks in a window (ten seconds). */
    public static final int WINDOW = 200;
    /** The key for work done for every town at once (snapshots, ambient citizens). */
    public static final UUID SERVER = new UUID(0, 0);

    /** One town's (or the server-wide work's) time over a window, in nanoseconds. */
    public static final class Usage {
        public final Map<Section, Long> sections = new EnumMap<>(Section.class);
        public long total, peak, deferred;
        public int overBudget;
        /** Models worked out off the server thread, and the time they took there (not part of {@link #total}). */
        public int models;
        public long modelNanos;
        long thisTick;

        void close() {
            peak = Math.max(peak, thisTick);
            if (thisTick > budgetNanos()) overBudget++;
            thisTick = 0;
        }
    }

    private static final int DEPTH = 8;
    private static final UUID[] CITY = new UUID[DEPTH];
    private static final Section[] SECTION = new Section[DEPTH];
    private static final long[] START = new long[DEPTH], CHILDREN = new long[DEPTH];
    private static int depth;

    private static Map<UUID, Usage> current = new HashMap<>(), last = new HashMap<>();
    private static final Set<UUID> touched = new HashSet<>();
    private static long serverNanos, lastServerNanos;
    private static int ticks, lastTicks;

    /** Starts timing a section of work for a town ({@link #SERVER} for work done for them all). Always pair with {@link #end()}. */
    public static void begin(UUID city, Section section) {
        if (depth >= DEPTH) { depth++; return; }
        CITY[depth] = city;
        SECTION[depth] = section;
        START[depth] = System.nanoTime();
        CHILDREN[depth] = 0;
        depth++;
    }

    public static void end() {
        depth--;
        if (depth >= DEPTH || depth < 0) { depth = Math.max(depth, 0); return; }
        long spent = System.nanoTime() - START[depth];
        long own = spent - CHILDREN[depth];
        if (depth > 0) CHILDREN[depth - 1] += spent;
        Usage u = current.computeIfAbsent(CITY[depth], k -> new Usage());
        u.sections.merge(SECTION[depth], own, Long::sum);
        u.total += own;
        u.thisTick += own;
        touched.add(CITY[depth]);
    }

    /** True once the town has used its time this tick (or the outer section running now has, with what's nested in it). */
    public static boolean over(UUID city) {
        Usage u = current.get(city);
        long spent = u == null ? 0 : u.thisTick;
        for (int i = 0; i < Math.min(depth, DEPTH); i++) if (city.equals(CITY[i])) { spent += System.nanoTime() - START[i] - CHILDREN[i]; break; }
        return spent >= budgetNanos();
    }

    /** Notes a model worked out off the server thread (called from that thread). */
    public static void background(UUID city, long nanos) {
        synchronized (BACKGROUND) {
            long[] b = BACKGROUND.computeIfAbsent(city, k -> new long[2]);
            b[0]++;
            b[1] += nanos;
        }
    }

    private static final Map<UUID, long[]> BACKGROUND = new HashMap<>();

    /** Notes a piece of work put off to a later tick because the town was over its budget. */
    public static void deferred(UUID city) {
        current.computeIfAbsent(city, k -> new Usage()).deferred++;
    }

    static long budgetNanos() {
        return Math.round(TownsteadConfig.TOWN_TICK_BUDGET.get() * 1_000_000);
    }

    @SubscribeEvent
    static void tickStart(ServerTickEvent.Pre event) {
        depth = 0;
    }

    @SubscribeEvent
    static void tickEnd(ServerTickEvent.Post event) {
        for (UUID city : touched) current.get(city).close();
        touched.clear();
        serverNanos += event.getServer().getTickTimesNanos()[event.getServer().getTickCount() % event.getServer().getTickTimesNanos().length];
        if (++ticks >= WINDOW) {
            synchronized (BACKGROUND) {
                BACKGROUND.forEach((city, b) -> {
                    Usage u = current.computeIfAbsent(city, k -> new Usage());
                    u.models = (int) b[0];
                    u.modelNanos = b[1];
                });
                BACKGROUND.clear();
            }
            last = current;
            current = new HashMap<>();
            lastServerNanos = serverNanos;
            lastTicks = ticks;
            serverNanos = 0;
            ticks = 0;
        }
    }

    /** The last full window: each town's usage (and {@link #SERVER}'s). */
    public static Map<UUID, Usage> lastWindow() {
        return last;
    }

    public static int windowTicks() {
        return lastTicks;
    }

    /** The server's average tick over the last window, all of it (worlds, entities, every mod), in nanoseconds. */
    public static long serverTick() {
        return lastTicks == 0 ? 0 : lastServerNanos / lastTicks;
    }

    public static @Nullable Usage of(UUID city) {
        return last.get(city);
    }

    private TownProfiler() {}
}
