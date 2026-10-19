package com.cloudkingtv.townstead.history;

import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.city.Stock;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadNode;
import com.cloudkingtv.townstead.road.RoadSegment;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Undo and redo for planner edits, per town. An entry holds the blocks it changed (before and after), the road
 * graph pieces it added and removed, and how it moved the town's stock. Undo puts all three back; a block the
 * player has changed since is left alone (and counted), so undo never wipes out hand-built work.
 * History lives in memory for the session and is capped at {@link #DEPTH} entries per town.
 */
public final class EditHistory {
    public static final int DEPTH = 32;

    /** The road graph pieces an edit added and removed. Changed segments appear in both. */
    public record GraphDiff(List<RoadNode> nodesAdded, List<RoadNode> nodesRemoved,
                            List<RoadSegment> segmentsAdded, List<RoadSegment> segmentsRemoved) {
        public static GraphDiff between(RoadNetwork before, RoadNetwork after) {
            List<RoadNode> na = after.nodes().stream().filter(n -> before.node(n.id()) != n).toList();
            List<RoadNode> nr = before.nodes().stream().filter(n -> after.node(n.id()) != n).toList();
            List<RoadSegment> sa = after.segments().stream().filter(s -> before.segment(s.id()) != s).toList();
            List<RoadSegment> sr = before.segments().stream().filter(s -> after.segment(s.id()) != s).toList();
            return new GraphDiff(na, nr, sa, sr);
        }

        void apply(RoadNetwork net, boolean forward) {
            List<RoadSegment> drop = forward ? segmentsRemoved : segmentsAdded, put = forward ? segmentsAdded : segmentsRemoved;
            List<RoadNode> dropN = forward ? nodesRemoved : nodesAdded, putN = forward ? nodesAdded : nodesRemoved;
            drop.forEach(s -> net.removeSegment(s.id()));
            dropN.forEach(n -> net.removeNode(n.id()));
            putN.forEach(net::putNode);
            put.forEach(net::putSegment);
        }
    }

    /**
     * One undoable edit. {@code undoExtra}/{@code redoExtra} restore state that isn't blocks, roads or stock (zones,
     * building records); they run after the blocks are put back.
     */
    public record Entry(Component label, List<BlockRecorder.Change> blocks, GraphDiff graph, Map<Identifier, Integer> stockDelta,
                        Runnable undoExtra, Runnable redoExtra) {
        public Entry(Component label, List<BlockRecorder.Change> blocks, GraphDiff graph, Map<Identifier, Integer> stockDelta) {
            this(label, blocks, graph, stockDelta, () -> {}, () -> {});
        }
    }

    public static final GraphDiff NO_GRAPH = new GraphDiff(List.of(), List.of(), List.of(), List.of());

    private static final Map<UUID, Deque<Entry>> UNDO = new HashMap<>(), REDO = new HashMap<>();

    public static void record(City city, Entry entry) {
        Deque<Entry> undo = UNDO.computeIfAbsent(city.id(), k -> new ArrayDeque<>());
        undo.push(entry);
        while (undo.size() > DEPTH) undo.removeLast();
        REDO.remove(city.id());
    }

    /** Stock after minus stock before. */
    public static Map<Identifier, Integer> delta(Map<Identifier, Integer> before, Map<Identifier, Integer> after) {
        Map<Identifier, Integer> d = new HashMap<>();
        after.forEach((k, v) -> { int diff = v - before.getOrDefault(k, 0); if (diff != 0) d.put(k, diff); });
        before.forEach((k, v) -> { if (!after.containsKey(k)) d.put(k, -v); });
        return d;
    }

    public static void undo(ServerPlayer player, City city) {
        step(player, city, false);
    }

    public static void redo(ServerPlayer player, City city) {
        step(player, city, true);
    }

    private static void step(ServerPlayer player, City city, boolean redo) {
        Deque<Entry> from = (redo ? REDO : UNDO).get(city.id());
        if (from == null || from.isEmpty()) {
            RoadEditor.notify(player, Component.translatable(redo ? "history.townstead.nothing_to_redo" : "history.townstead.nothing_to_undo"), false);
            return;
        }
        Entry e = from.peek();
        ServerLevel level = player.level();
        CityManager cities = CityManager.get(level.getServer());
        // Redo spends materials again: refuse if the storehouse can't cover them now.
        if (redo) {
            for (var d : e.stockDelta().entrySet()) {
                if (d.getValue() < 0 && city.stock().getOrDefault(d.getKey(), 0) < -d.getValue()) {
                    RoadEditor.notify(player, Component.translatable("history.townstead.redo_short", new net.minecraft.world.item.ItemStack(Stock.item(d.getKey())).getHoverName()), false);
                    return;
                }
            }
        }
        from.pop();
        int skipped = 0;
        List<BlockRecorder.Change> blocks = e.blocks();
        for (int i = 0; i < blocks.size(); i++) {
            BlockRecorder.Change c = blocks.get(redo ? i : blocks.size() - 1 - i);
            BlockState expect = redo ? c.before() : c.after(), target = redo ? c.after() : c.before();
            BlockState now = level.getBlockState(c.pos());
            if (!now.equals(expect)) { if (!now.equals(target)) skipped++; continue; }
            level.setBlock(c.pos(), target, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        }
        RoadNetwork net = RoadNetwork.get(level);
        e.graph().apply(net, redo);
        (redo ? e.redoExtra() : e.undoExtra()).run();
        cities.editStock(level.getServer(), city, stock -> e.stockDelta().forEach((id, n) -> {
            int change = redo ? n : -n;
            if (change > 0) Stock.add(stock, Stock.item(id), change);
            else Stock.take(stock, Stock.item(id), -change); // takes what's there if some was withdrawn since
        }));
        (redo ? UNDO : REDO).computeIfAbsent(city.id(), k -> new ArrayDeque<>()).push(e);
        RoadEditor.sync(level);
        level.playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.8f, redo ? 1.2f : 0.8f);
        Component msg = Component.translatable(redo ? "history.townstead.redone" : "history.townstead.undone", e.label());
        if (skipped > 0) msg = Component.translatable("history.townstead.skipped", msg, skipped);
        RoadEditor.notify(player, msg, true);
        // Undoing a road's construction takes it away again: so do its coach stops.
        com.cloudkingtv.townstead.transit.CoachLines.pruneOrphans(player, city);
    }

    public static void clear() {
        UNDO.clear();
        REDO.clear();
    }

    private EditHistory() {}
}
