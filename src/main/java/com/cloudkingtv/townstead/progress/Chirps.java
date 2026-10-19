package com.cloudkingtv.townstead.progress;

import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.Residents;
import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.network.ChirpPayload;
import com.cloudkingtv.townstead.zone.LandData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Chirps: short posts from the town's own residents about what's happening, like Cities: Skylines' Chirper. Each has
 * an author (a real resident, by name), a line of text from a few variants per event, and a place the planner can
 * fly to. Routine news is rate-limited so the feed stays readable; milestones, abandonment and debt always get through.
 */
public final class Chirps {
    /** Ticks between routine chirps per town. */
    private static final long ROUTINE_GAP = 400;
    private static final Map<UUID, Long> LAST = new HashMap<>();
    /** How many text variants each event key has (chirp.townstead.<event>.<n>). */
    private static final Map<String, Integer> VARIANTS = Map.ofEntries(
            Map.entry("moved_in", 3), Map.entry("opened", 3), Map.entry("levelled", 3), Map.entry("abandoned", 3),
            Map.entry("repaired", 2), Map.entry("service", 3), Map.entry("milestone", 2), Map.entry("land", 2),
            Map.entry("broke", 2), Map.entry("solvent", 2), Map.entry("trouble", 3), Map.entry("landmark", 2));

    /**
     * Posts a chirp to the town's mayor. {@code important} ones skip the rate limit.
     *
     * @param args arguments after the author's name (the building's or the town's name, a number...)
     */
    public static void post(ServerLevel level, City city, String event, BlockPos at, boolean important, Object... args) {
        long now = level.getGameTime();
        if (!important) {
            Long last = LAST.get(city.id());
            if (last != null && now - last < ROUTINE_GAP) return;
        }
        LAST.put(city.id(), now);
        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(city.owner());
        if (owner == null) return;
        int n = VARIANTS.getOrDefault(event, 1);
        int variant = (int) Math.floorMod(now * 31 + event.hashCode(), (long) n);
        Component text = Component.translatable("chirp.townstead." + event + "." + variant, args);
        PacketDistributor.sendToPlayer(owner, new ChirpPayload(city.id(), author(level, city, now), text, at.immutable(), event));
    }

    /** A resident of the town to sign the post (the founder before anyone lives there). */
    static String author(ServerLevel level, City city, long seed) {
        List<Building> homes = LandData.get(level).buildingsOf(city.id()).stream().filter(b -> b.zone.residential() && b.working()).toList();
        if (homes.isEmpty()) return city.ownerName();
        Building home = homes.get((int) Math.floorMod(seed, (long) homes.size()));
        List<Residents.Household> households = Residents.households(home);
        if (households.isEmpty()) return city.ownerName();
        List<Residents.Person> people = households.get((int) Math.floorMod(seed / 7, (long) households.size())).members();
        return people.get((int) Math.floorMod(seed / 13, (long) people.size())).name();
    }

    private Chirps() {}
}
