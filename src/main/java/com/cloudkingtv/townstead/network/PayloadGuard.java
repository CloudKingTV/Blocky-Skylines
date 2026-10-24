package com.cloudkingtv.townstead.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Rate limits for what players send the server. Every serverbound payload draws from its sender's bucket of tokens,
 * which refills by one a tick up to {@link #CAPACITY}: a player clicking as fast as they can never notices, while a
 * client flooding road builds or paint strokes gets dropped (with one warning) instead of costing the server time.
 * Edits that touch the world (building a road, placing a service, undo) cost more than a slider tick.
 */
public final class PayloadGuard {
    public static final int CAPACITY = 40;
    /** Tokens each payload costs: a quick setting, or an edit that plans and writes blocks. */
    public static final int LIGHT = 1, HEAVY = 4;
    /** Ticks between "slow down" warnings to the same player. */
    private static final int WARN_EVERY = 100;

    private record Bucket(double tokens, long tick, long warned) {}

    private static final Map<UUID, Bucket> BUCKETS = new HashMap<>();
    private static long dropped;

    /** Takes {@code cost} tokens from the player's bucket; false (and the payload is dropped) if they're out. */
    public static boolean allow(ServerPlayer player, int cost) {
        long now = player.level().getServer().getTickCount();
        Bucket b = BUCKETS.get(player.getUUID());
        double tokens = b == null ? CAPACITY : Math.min(CAPACITY, b.tokens + Math.max(0, now - b.tick));
        long warned = b == null ? Long.MIN_VALUE / 2 : b.warned;
        if (tokens < cost) {
            dropped++;
            if (now - warned >= WARN_EVERY) {
                player.sendOverlayMessage(Component.translatable("message.townstead.slow_down"));
                warned = now;
            }
            BUCKETS.put(player.getUUID(), new Bucket(tokens, now, warned));
            return false;
        }
        BUCKETS.put(player.getUUID(), new Bucket(tokens - cost, now, warned));
        return true;
    }

    /** Payloads dropped since the server started, for {@code /townstead perf}. */
    public static long dropped() {
        return dropped;
    }

    public static void forget(UUID player) {
        BUCKETS.remove(player);
    }

    /** True if every value is a real number (a crafted payload can carry NaN or infinity, which slip past range checks). */
    public static boolean finite(double... values) {
        for (double v : values) if (!Double.isFinite(v)) return false;
        return true;
    }

    private PayloadGuard() {}
}
