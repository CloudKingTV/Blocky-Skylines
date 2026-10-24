package com.cloudkingtv.townstead;

import com.cloudkingtv.townstead.building.Building;
import com.cloudkingtv.townstead.building.Residents;
import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.district.Civics;
import com.cloudkingtv.townstead.economy.Budget;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadSegment;
import com.cloudkingtv.townstead.sim.TownModel;
import com.cloudkingtv.townstead.zone.LandData;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Scratch-only test probe for Phase 10: every 100 ticks the dedicated server logs one line per town with what its
 * clients should see (buildings, people, progress, treasury, taxes, budgets, stock, roads, civics), and the
 * multiplayer bots log the same line from their synced copies. Comparing the two finds desyncs.
 */
@EventBusSubscriber(modid = Townstead.MODID)
public final class SyncProbe {
    @SubscribeEvent
    static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        if (!(level.getServer() instanceof DedicatedServer) || level.getGameTime() % 100 != 0) return;
        LandData land = LandData.get(level);
        RoadNetwork roads = RoadNetwork.get(level);
        if (level.getGameTime() % 6000 == 0) for (City c : CityManager.get(level.getServer()).all()) equivalence(level, c);
        for (City c : CityManager.get(level.getServer()).all()) {
            System.out.println("[Sync] S t=" + level.getGameTime() + " " + line(c.id(), land.buildingsOf(c.id()), roads, c.civics(), c.tiles().size(),
                    c.treasury(), c.budget(), c.stock()));
        }
    }

    /** The comparable state of a town, the same on both sides when they agree. */
    public static String line(UUID id, Collection<Building> town, RoadNetwork roads, Civics civics, int tiles, long treasury, Budget budget,
                              Map<Identifier, Integer> stock) {
        int people = 0;
        long progress = 0;
        for (Building b : town) {
            if (b.working()) people += Residents.people(b);
            progress += b.progress;
        }
        int segments = 0;
        for (RoadSegment s : roads.segments()) if (s.city().equals(id)) segments++;
        long goods = 0;
        for (int n : stock.values()) goods += n;
        return "city=" + id.toString().substring(0, 8) + " fp=" + Long.toHexString(stable(town, civics))
                + " n=" + town.size() + " people=" + people + " prog=" + progress + " treasury=" + treasury
                + " taxes=" + Arrays.toString(budget.taxes) + " budgets=" + Arrays.toString(budget.budgets) + " stock=" + goods + " segs=" + segments
                + " tiles=" + tiles;
    }

    /**
     * What {@link TownModel#fingerprint} covers, hashed the same in every JVM (that one hashes enums, whose hash codes
     * differ from one process to the next, which is fine for a cache key but not for comparing two processes).
     */
    static long stable(Collection<Building> town, Civics civics) {
        long h = 17;
        for (Building b : town) {
            h = h * 31 + b.id;
            h = h * 31 + b.state.ordinal() * 8 + b.level + (b.upgrading ? 4 : 0);
            h = h * 31 + b.households * 1000 + b.jobs;
            h = h * 31 + b.spec.hashCode();
            h = h * 31 + (b.fire > 0 ? 1 : 0);
        }
        for (var l : civics.lines()) h = h * 31 + l.id() * 64 + l.color().ordinal() + 1000L * l.stops().size();
        for (var d : civics.districts()) {
            h = h * 31 + d.id() * 7919L + d.cells().size() + 100_000L * d.spec().ordinal();
            for (var p : d.policies()) h = h * 31 + p.ordinal();
        }
        for (var p : civics.policies()) h = h * 31 + p.ordinal();
        return h;
    }

    /**
     * The model's indexed lookups (service reach, homes and workplaces nearby, counts) against plain scans of the whole
     * town, the way they were worked out before Phase 10: any difference is a bug in the index.
     */
    static void equivalence(ServerLevel level, City c) {
        TownModel m = com.cloudkingtv.townstead.economy.TownEconomy.model(level, c);
        int checks = 0, wrong = 0;
        for (Building b : m.town) {
            for (com.cloudkingtv.townstead.service.ServiceType s : com.cloudkingtv.townstead.service.ServiceType.values()) {
                boolean old = com.cloudkingtv.townstead.service.Utilities.covered(b, m.town, s, m.radius(s));
                com.cloudkingtv.townstead.service.ServiceType also = switch (s) {
                    case GUARD_HOUSE, FIRE_STATION -> com.cloudkingtv.townstead.service.ServiceType.CASTLE_KEEP;
                    case SCHOOL -> com.cloudkingtv.townstead.service.ServiceType.GREAT_LIBRARY;
                    default -> null;
                };
                if (!old && also != null) old = com.cloudkingtv.townstead.service.Utilities.covered(b, m.town, also, m.radius(also));
                checks++;
                if (old != m.covered(b, s)) wrong++;
            }
            int homes = 0;
            for (Building o : m.town) if (o.zone.residential() && o.working() && o.distanceTo(b) <= 32) homes += Residents.people(o);
            checks++;
            if (homes != m.residentsNear(b, 32)) wrong++;
            for (var need : com.cloudkingtv.townstead.zone.ZoneType.Need.values()) {
                int n = 0;
                for (Building o : m.town) if (o.id != b.id && o.zone.need == need && o.working() && o.distanceTo(b) <= 64) n++;
                checks++;
                if (n != m.workingNear(b, 64, need)) wrong++;
            }
        }
        for (var need : com.cloudkingtv.townstead.zone.ZoneType.Need.values()) {
            int n = 0;
            for (Building o : m.town) if (o.zone.need == need && o.working()) n++;
            checks++;
            if (n != m.working(need)) wrong++;
        }
        System.out.println("[Equiv] " + c.name() + ": " + checks + " checks, " + wrong + " differ");
    }

    private SyncProbe() {}
}
