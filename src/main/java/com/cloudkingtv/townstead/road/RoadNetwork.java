package com.cloudkingtv.townstead.road;

import com.cloudkingtv.townstead.Townstead;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec2;
import org.jspecify.annotations.Nullable;

/**
 * The road graph of one dimension: nodes (ends and junctions) and the segments between them. Saved per level in
 * data/townstead/roads.dat. The client keeps a copy (from {@code RoadSnapshotPayload}) for snapping and previews.
 */
public final class RoadNetwork extends SavedData {
    public static final Codec<RoadNetwork> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("data_version", 1).forGetter(n -> 1),
            Codec.INT.fieldOf("next_id").forGetter(n -> n.nextId),
            RoadNode.CODEC.listOf().fieldOf("nodes").forGetter(n -> List.copyOf(n.nodes.values())),
            RoadSegment.CODEC.listOf().fieldOf("segments").forGetter(n -> List.copyOf(n.segments.values()))
    ).apply(i, (version, next, nodes, segments) -> new RoadNetwork(next, nodes, segments)));

    public static final SavedDataType<RoadNetwork> TYPE = new SavedDataType<>(Townstead.id("roads"), RoadNetwork::new, CODEC);

    private final Map<Integer, RoadNode> nodes = new LinkedHashMap<>();
    private final Map<Integer, RoadSegment> segments = new LinkedHashMap<>();
    private int nextId = 1;
    private final Map<Integer, Map<Long, RoadRaster.Cell>> rasterCache = new HashMap<>();

    public RoadNetwork() {}

    public RoadNetwork(int nextId, List<RoadNode> nodes, List<RoadSegment> segments) {
        this.nextId = nextId;
        nodes.forEach(n -> this.nodes.put(n.id(), n));
        segments.forEach(s -> this.segments.put(s.id(), s));
    }

    public static RoadNetwork get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    // ---------------------------------------------------------------- queries

    public Collection<RoadNode> nodes() { return nodes.values(); }
    public Collection<RoadSegment> segments() { return segments.values(); }
    public int nextId() { return nextId; }
    public @Nullable RoadNode node(int id) { return nodes.get(id); }
    public @Nullable RoadSegment segment(int id) { return segments.get(id); }

    public List<RoadSegment> segmentsAt(int nodeId) {
        List<RoadSegment> out = new ArrayList<>();
        for (RoadSegment s : segments.values()) if (s.a() == nodeId || s.b() == nodeId) out.add(s);
        return out;
    }

    public Optional<RoadNode> nearestNode(double x, double z, double maxDistance) {
        RoadNode best = null;
        double bestD = maxDistance;
        for (RoadNode n : nodes.values()) {
            double d = n.distance(x, z);
            if (d <= bestD) { bestD = d; best = n; }
        }
        return Optional.ofNullable(best);
    }

    public record SegmentHit(RoadSegment segment, double t, double distance) {}

    /** The segment whose centreline passes closest to (x, z), if within its half-width plus {@code slack}. */
    public Optional<SegmentHit> nearestSegment(double x, double z, double slack) {
        SegmentHit best = null;
        for (RoadSegment s : segments.values()) {
            double reach = s.type().halfWidth() + slack;
            if (!inBounds(s, x, z, reach)) continue;
            double t = s.curve().closestT(x, z);
            double d = s.curve().distance(t, x, z);
            if (d <= reach && (best == null || d < best.distance())) best = new SegmentHit(s, t, d);
        }
        return Optional.ofNullable(best);
    }

    private static boolean inBounds(RoadSegment s, double x, double z, double pad) {
        RoadCurve c = s.curve();
        return x >= Math.min(c.ax(), Math.min(c.cx(), c.bx())) - pad && x <= Math.max(c.ax(), Math.max(c.cx(), c.bx())) + pad
                && z >= Math.min(c.az(), Math.min(c.cz(), c.bz())) - pad && z <= Math.max(c.az(), Math.max(c.cz(), c.bz())) + pad;
    }

    /**
     * Every block column a town's roads cover (read-only; worked out once per change to the network, since lots,
     * utilities and reviews all ask for it).
     */
    public Map<Long, RoadRaster.Cell> columns(java.util.UUID city) {
        return columns.computeIfAbsent(city, c -> {
            Map<Long, RoadRaster.Cell> out = new HashMap<>();
            for (RoadSegment s : segments.values()) if (s.city().equals(c)) out.putAll(raster(s));
            return java.util.Collections.unmodifiableMap(out);
        });
    }

    private final Map<java.util.UUID, Map<Long, RoadRaster.Cell>> columns = new HashMap<>();

    /** Every change to the network goes through here: saved, and the cached columns worked out afresh. */
    @Override
    public void setDirty() {
        super.setDirty();
        columns.clear();
    }

    public Map<Long, RoadRaster.Cell> raster(RoadSegment s) {
        return rasterCache.computeIfAbsent(s.id(), id -> RoadRaster.segment(s.id(), s.type(), s.curve(), s.heights()));
    }

    /** Whether a node needs a paved junction cap: three or more roads, or two meeting at a real angle. */
    public boolean needsCap(int nodeId) {
        List<RoadSegment> at = segmentsAt(nodeId);
        if (at.size() >= 3) return true;
        if (at.size() < 2) return false;
        Vec2 d0 = outward(at.get(0), nodeId), d1 = outward(at.get(1), nodeId);
        double dot = d0.x * d1.x + d0.y * d1.y; // straight through = -1
        return at.get(0).type() != at.get(1).type() || dot > -0.87; // more than ~30 degrees off straight
    }

    /** Unit direction leaving the node along the segment. */
    public static Vec2 outward(RoadSegment s, int nodeId) {
        Vec2 d = s.a() == nodeId ? s.curve().direction(0) : s.curve().direction(1);
        return s.a() == nodeId ? d : new Vec2(-d.x, -d.y);
    }

    /**
     * The winning road column for every block column touched by the given segments, including junction caps of
     * their end nodes, merged with every other built road that overlaps them.
     */
    public Map<Long, RoadRaster.Cell> merged(Collection<RoadSegment> focus) {
        Map<Long, RoadRaster.Cell> out = new HashMap<>();
        for (RoadSegment s : focus) mergeInto(out, raster(s));
        for (RoadSegment s : focus) {
            for (int nodeId : new int[] {s.a(), s.b()}) {
                if (needsCap(nodeId)) mergeInto(out, cap(nodeId));
            }
        }
        List<RoadSegment> others = new ArrayList<>();
        for (RoadSegment o : segments.values()) {
            if (focus.contains(o)) continue;
            for (RoadSegment s : focus) {
                if (overlaps(o, s)) { others.add(o); break; }
            }
        }
        for (RoadSegment o : others) {
            mergeOnly(out, raster(o));
            for (int nodeId : new int[] {o.a(), o.b()}) {
                if (needsCap(nodeId)) mergeOnly(out, cap(nodeId));
            }
        }
        return out;
    }

    /** Merge cells from {@code extra} where they win, without adding columns {@code out} doesn't have. */
    private static void mergeOnly(Map<Long, RoadRaster.Cell> out, Map<Long, RoadRaster.Cell> extra) {
        extra.forEach((k, c) -> out.computeIfPresent(k, (key, old) -> c.beats(old) ? c : old));
    }

    private static void mergeInto(Map<Long, RoadRaster.Cell> out, Map<Long, RoadRaster.Cell> extra) {
        extra.forEach((k, c) -> out.merge(k, c, (old, neu) -> neu.beats(old) ? neu : old));
    }

    public Map<Long, RoadRaster.Cell> cap(int nodeId) {
        RoadNode node = nodes.get(nodeId);
        double half = 0;
        boolean walk = false;
        for (RoadSegment s : segmentsAt(nodeId)) {
            half = Math.max(half, RoadRaster.carriageHalf(s.type()));
            walk |= RoadRaster.hasSidewalk(s.type());
        }
        return RoadRaster.junctionCap(node, half, walk);
    }

    /** Merged winner for each of the given columns among every built road and junction cap. */
    public Map<Long, RoadRaster.Cell> winners(Collection<Long> columns) {
        Map<Long, RoadRaster.Cell> out = new HashMap<>();
        for (RoadSegment s : segments.values()) {
            Map<Long, RoadRaster.Cell> r = raster(s);
            for (long k : columns) {
                RoadRaster.Cell c = r.get(k);
                if (c != null) out.merge(k, c, (old, neu) -> neu.beats(old) ? neu : old);
            }
        }
        for (RoadNode n : nodes.values()) {
            if (!needsCap(n.id())) continue;
            Map<Long, RoadRaster.Cell> r = cap(n.id());
            for (long k : columns) {
                RoadRaster.Cell c = r.get(k);
                if (c != null) out.merge(k, c, (old, neu) -> neu.beats(old) ? neu : old);
            }
        }
        return out;
    }

    static boolean overlaps(RoadSegment a, RoadSegment b) {
        RoadCurve p = a.curve(), q = b.curve();
        double pad = a.type().halfWidth() + b.type().halfWidth() + 2;
        double pMinX = Math.min(p.ax(), Math.min(p.cx(), p.bx())), pMaxX = Math.max(p.ax(), Math.max(p.cx(), p.bx()));
        double pMinZ = Math.min(p.az(), Math.min(p.cz(), p.bz())), pMaxZ = Math.max(p.az(), Math.max(p.cz(), p.bz()));
        double qMinX = Math.min(q.ax(), Math.min(q.cx(), q.bx())), qMaxX = Math.max(q.ax(), Math.max(q.cx(), q.bx()));
        double qMinZ = Math.min(q.az(), Math.min(q.cz(), q.bz())), qMaxZ = Math.max(q.az(), Math.max(q.cz(), q.bz()));
        return pMinX - pad <= qMaxX && qMinX - pad <= pMaxX && pMinZ - pad <= qMaxZ && qMinZ - pad <= pMaxZ;
    }

    // ---------------------------------------------------------------- edits (server; client replaces wholesale)

    /** A scratch copy to plan an edit on; {@link #adopt} it once the edit is accepted. */
    public RoadNetwork copy() {
        return new RoadNetwork(nextId, List.copyOf(nodes.values()), List.copyOf(segments.values()));
    }

    public void adopt(RoadNetwork planned) {
        nodes.clear();
        nodes.putAll(planned.nodes);
        segments.clear();
        segments.putAll(planned.segments);
        nextId = planned.nextId;
        rasterCache.clear();
        rasterCache.putAll(planned.rasterCache);
        setDirty();
    }

    public RoadNode addNode(double x, int y, double z) {
        RoadNode n = new RoadNode(nextId++, x, y, z);
        nodes.put(n.id(), n);
        setDirty();
        return n;
    }

    public RoadSegment addSegment(RoadSegment template) {
        RoadSegment s = new RoadSegment(nextId++, template.city(), template.type(), template.a(), template.b(), template.curve(),
                template.heights(), template.placedAt(), template.decorations());
        segments.put(s.id(), s);
        setDirty();
        return s;
    }

    public void replaceSegment(RoadSegment s) {
        segments.put(s.id(), s);
        rasterCache.remove(s.id());
        setDirty();
    }

    /** Puts back a node exactly as it was (undo/redo). */
    public void putNode(RoadNode n) {
        nodes.put(n.id(), n);
        nextId = Math.max(nextId, n.id() + 1);
        setDirty();
    }

    public void removeNode(int id) {
        nodes.remove(id);
        setDirty();
    }

    /** Puts back a segment exactly as it was, keeping its id (undo/redo). */
    public void putSegment(RoadSegment s) {
        segments.put(s.id(), s);
        rasterCache.remove(s.id());
        nextId = Math.max(nextId, s.id() + 1);
        setDirty();
    }

    public void removeSegment(int id) {
        segments.remove(id);
        rasterCache.remove(id);
        setDirty();
    }

    /** Drops nodes no segment uses any more. */
    public void pruneNodes() {
        nodes.keySet().removeIf(id -> segments.values().stream().noneMatch(s -> s.a() == id || s.b() == id));
        setDirty();
    }
}
