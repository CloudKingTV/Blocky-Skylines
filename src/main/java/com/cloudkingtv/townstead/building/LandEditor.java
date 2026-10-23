package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.city.City;
import com.cloudkingtv.townstead.city.CityManager;
import com.cloudkingtv.townstead.city.Stock;
import com.cloudkingtv.townstead.history.BlockRecorder;
import com.cloudkingtv.townstead.history.EditHistory;
import com.cloudkingtv.townstead.road.Material;
import com.cloudkingtv.townstead.road.RoadEditor;
import com.cloudkingtv.townstead.road.RoadNetwork;
import com.cloudkingtv.townstead.road.RoadRaster;
import com.cloudkingtv.townstead.zone.LandData;
import com.cloudkingtv.townstead.zone.ZoneRules;
import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import org.jspecify.annotations.Nullable;

/** Planner edits to zoning and growables: painting zones and bulldozing buildings. Both undoable. */
public final class LandEditor {
    /** Paints (or with a null zone, clears) cells. Only cells with road frontage on the town's land take a zone. */
    public static void paint(ServerPlayer player, City city, @Nullable ZoneType zone, List<Long> cells) {
        ServerLevel level = player.level();
        if (zone != null && !com.cloudkingtv.townstead.progress.Milestone.unlocked(city.milestone(), zone)) {
            RoadEditor.notify(player, Component.translatable("milestone.townstead.locked", zone.displayName(),
                    com.cloudkingtv.townstead.progress.Milestone.unlocking(zone).displayName()), false);
            return;
        }
        LandData land = LandData.get(level);
        var roads = ZoneRules.roadColumns(RoadNetwork.get(level), city.id());
        Map<Long, LandData.Zoned> before = new HashMap<>(), after = new HashMap<>();
        for (long cell : cells.stream().distinct().limit(512).toList()) {
            LandData.Zoned old = land.zone(cell);
            if (old != null && !old.city().equals(city.id())) continue;
            Building standing = land.buildingAt(cell);
            if (standing != null && standing.civic()) continue; // services aren't zoned
            LandData.Zoned neu = null;
            if (zone != null) {
                if (ZoneRules.zonable(RoadRaster.keyX(cell), RoadRaster.keyZ(cell), roads, city.tiles()) == null) continue;
                neu = new LandData.Zoned(city.id(), zone);
            }
            if (java.util.Objects.equals(old, neu)) continue;
            before.put(cell, old);
            after.put(cell, neu);
        }
        if (after.isEmpty()) {
            RoadEditor.notify(player, Component.translatable(zone == null ? "zone.townstead.nothing_cleared" : "zone.townstead.nothing_zoned"), false);
            return;
        }
        Runnable apply = () -> { after.forEach(land::setZone); TownGrowth.markDirty(); };
        Runnable revert = () -> { before.forEach(land::setZone); TownGrowth.markDirty(); };
        apply.run();
        Component label = zone == null ? Component.translatable("zone.townstead.cleared", after.size())
                : Component.translatable("zone.townstead.zoned", after.size(), zone.displayName());
        EditHistory.record(city, new EditHistory.Entry(label, List.of(), EditHistory.NO_GRAPH, Map.of(), revert, apply));
        level.playSound(null, player.blockPosition(), SoundEvents.BRUSH_GENERIC, SoundSource.PLAYERS, 0.7f, 1.2f);
        RoadEditor.notify(player, label, true);
    }

    /** Places a service or utility on a lot; undo takes it down again with a full refund. */
    public static void placeService(ServerPlayer player, City city, com.cloudkingtv.townstead.service.ServiceType service, long cell) {
        ServerLevel level = player.level();
        if (!com.cloudkingtv.townstead.progress.Milestone.unlocked(city.milestone(), service)) {
            RoadEditor.notify(player, Component.translatable("milestone.townstead.locked", service.displayName(),
                    com.cloudkingtv.townstead.progress.Milestone.unlocking(service).displayName()), false);
            return;
        }
        Object result = TownGrowth.placeCivic(level, city, service, RoadRaster.keyX(cell), RoadRaster.keyZ(cell));
        if (result instanceof String reason) {
            RoadEditor.notify(player, Component.translatable(reason), false);
            return;
        }
        Building placed = ((Building) result).copy();
        LandData land = LandData.get(level);
        Runnable remove = () -> {
            Building b = land.building(placed.id);
            if (b != null) takeDown(level, city, b, true, null);
        };
        Runnable restore = () -> TownGrowth.placeCivic(level, city, service, placed.cellX, placed.cellZ);
        EditHistory.record(city, new EditHistory.Entry(Component.translatable("service.townstead.placed", service.displayName()),
                List.of(), EditHistory.NO_GRAPH, Map.of(), remove, restore));
        level.playSound(null, player.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.PLAYERS, 0.8f, 1.0f);
    }

    /** Removes a building's blocks (those still as built) and record, refunding materials. Optionally journals the blocks. */
    static void takeDown(ServerLevel level, City city, Building b, boolean fullRefund, @Nullable BlockRecorder journal) {
        BuildingGenerator.Plan plan = TownGrowth.plan(b);
        BlockRecorder rec = journal != null ? journal : new BlockRecorder(level);
        Map<Material, Integer> refund = new EnumMap<>(Material.class);
        List<BuildingGenerator.Step> steps = plan.steps();
        for (int i = Math.min(b.progress, steps.size()) - 1; i >= 0; i--) {
            BuildingGenerator.Step s = steps.get(i);
            if (!rec.get(s.pos()).is(s.state().getBlock())) continue;
            Material m = TownGrowth.charged(plan, i); // back what it cost, no more
            if (m != null) refund.merge(m, 1, Integer::sum);
            if (s.state().getBlock() instanceof DoorBlock) rec.set(s.pos().above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            rec.set(s.pos(), s.pos().getY() < b.baseY ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (BlockPos base : plan.scaffold()) {
            for (int y = base.getY() + 26; y >= base.getY(); y--) {
                BlockPos p = new BlockPos(base.getX(), y, base.getZ());
                if (rec.get(p).is(Blocks.SCAFFOLDING)) rec.set(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
        if (!TownGrowth.isFree(level, city)) {
            CityManager.get(level.getServer()).editStock(level.getServer(), city,
                    stock -> refund.forEach((m, n) -> Stock.add(stock, m.icon().getItem(), fullRefund ? n : n / 2)));
        }
        LandData.get(level).removeBuilding(b.id);
        TownGrowth.forget(b.id);
        TownGrowth.markDirty();
    }

    /** Takes a building down to the grass. Materials come back in full for an unfinished site, half otherwise. */
    public static void bulldoze(ServerPlayer player, City city, int buildingId) {
        ServerLevel level = player.level();
        LandData land = LandData.get(level);
        Building b = land.building(buildingId);
        if (b == null || !b.city.equals(city.id())) return;
        BuildingGenerator.Plan plan = TownGrowth.plan(b);
        BlockRecorder rec = new BlockRecorder(level);
        Map<Material, Integer> refund = new EnumMap<>(Material.class);
        List<BuildingGenerator.Step> steps = plan.steps();
        for (int i = Math.min(b.progress, steps.size()) - 1; i >= 0; i--) {
            BuildingGenerator.Step s = steps.get(i);
            if (!rec.get(s.pos()).is(s.state().getBlock())) continue; // changed by hand since: leave it (panes and stairs reshape, so compare blocks)
            Material m = TownGrowth.charged(plan, i); // back what it cost, no more
            if (m != null) refund.merge(m, 1, Integer::sum);
            if (s.state().getBlock() instanceof DoorBlock) rec.set(s.pos().above(), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            boolean ground = s.pos().getY() < b.baseY;
            rec.set(s.pos(), ground ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        for (BlockPos base : plan.scaffold()) {
            for (int y = base.getY() + 10; y >= base.getY(); y--) {
                BlockPos p = new BlockPos(base.getX(), y, base.getZ());
                if (rec.get(p).is(Blocks.SCAFFOLDING)) rec.set(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
        boolean unfinished = b.state != Building.State.ACTIVE;
        CityManager cities = CityManager.get(level.getServer());
        Map<Identifier, Integer> stockBefore = Map.copyOf(city.stock());
        if (!TownGrowth.isFree(level, city)) {
            cities.editStock(level.getServer(), city, stock -> refund.forEach((m, n) -> Stock.add(stock, m.icon().getItem(), unfinished ? n : n / 2)));
        }
        Building saved = b.copy();
        Runnable remove = () -> { land.removeBuilding(saved.id); TownGrowth.forget(saved.id); TownGrowth.markDirty(); };
        Runnable restore = () -> { land.putBuilding(saved.copy()); TownGrowth.markDirty(); };
        remove.run();
        Component kind = Component.translatable("building.townstead.kind." + plan.kind());
        Component label = Component.translatable("building.townstead.bulldozed", kind);
        EditHistory.record(city, new EditHistory.Entry(label, rec.changes(), EditHistory.NO_GRAPH,
                EditHistory.delta(stockBefore, city.stock()), restore, remove));
        level.playSound(null, plan.min(), SoundEvents.WOOD_BREAK, SoundSource.BLOCKS, 1f, 0.7f);
        RoadEditor.notify(player, label, true);
    }

    private LandEditor() {}
}
