package com.cloudkingtv.townstead.zone;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.building.Building;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Zoning and growable buildings of one dimension (data/townstead/land.dat). The client keeps a copy for overlays,
 * the zoning tool and building info.
 */
public final class LandData extends SavedData {
    public record Zoned(UUID city, ZoneType type) {}

    private record ZoneEntry(int x, int z, UUID city, ZoneType type) {
        static final Codec<ZoneEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("x").forGetter(ZoneEntry::x),
                Codec.INT.fieldOf("z").forGetter(ZoneEntry::z),
                UUIDUtil.CODEC.fieldOf("city").forGetter(ZoneEntry::city),
                ZoneType.CODEC.fieldOf("type").forGetter(ZoneEntry::type)
        ).apply(i, ZoneEntry::new));
    }

    public static final Codec<LandData> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("next_id", 1).forGetter(d -> d.nextId),
            ZoneEntry.CODEC.listOf().optionalFieldOf("zones", List.of()).forGetter(d -> d.zones.entrySet().stream()
                    .map(e -> new ZoneEntry(com.cloudkingtv.townstead.road.RoadRaster.keyX(e.getKey()),
                            com.cloudkingtv.townstead.road.RoadRaster.keyZ(e.getKey()), e.getValue().city(), e.getValue().type()))
                    .toList()),
            Building.CODEC.listOf().optionalFieldOf("buildings", List.of()).forGetter(d -> List.copyOf(d.buildings.values()))
    ).apply(i, LandData::new));

    public static final SavedDataType<LandData> TYPE = new SavedDataType<>(Townstead.id("land"), LandData::new, CODEC);

    private final Map<Long, Zoned> zones = new HashMap<>();
    private final Map<Integer, Building> buildings = new LinkedHashMap<>();
    private final Map<Long, Integer> buildingByCell = new HashMap<>();
    private int nextId = 1;

    public LandData() {}

    private LandData(int nextId, List<ZoneEntry> zones, List<Building> buildings) {
        this.nextId = nextId;
        zones.forEach(z -> this.zones.put(ZoneRules.cellKey(z.x(), z.z()), new Zoned(z.city(), z.type())));
        buildings.forEach(this::putBuilding);
    }

    public static LandData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public LandData copy() {
        LandData d = new LandData();
        d.nextId = nextId;
        d.zones.putAll(zones);
        buildings.values().forEach(b -> d.putBuilding(b.copy()));
        return d;
    }

    // ---------------------------------------------------------------- zones

    public @Nullable Zoned zone(long cell) { return zones.get(cell); }
    public Map<Long, Zoned> zones() { return java.util.Collections.unmodifiableMap(zones); }

    public void setZone(long cell, @Nullable Zoned zone) {
        if (zone == null) zones.remove(cell);
        else zones.put(cell, zone);
        setDirty();
    }

    // ---------------------------------------------------------------- buildings

    public Collection<Building> buildings() { return buildings.values(); }
    public @Nullable Building building(int id) { return buildings.get(id); }

    public @Nullable Building buildingAt(long cell) {
        Integer id = buildingByCell.get(cell);
        return id == null ? null : buildings.get(id);
    }

    public int newId() {
        setDirty();
        return nextId++;
    }

    public void putBuilding(Building b) {
        buildings.put(b.id, b);
        for (long cell : b.cellKeys()) buildingByCell.put(cell, b.id);
        nextId = Math.max(nextId, b.id + 1);
        setDirty();
    }

    public void removeBuilding(int id) {
        Building b = buildings.remove(id);
        if (b != null) for (long cell : b.cellKeys()) buildingByCell.remove(cell, id);
        setDirty();
    }

    public List<Building> buildingsOf(UUID city) {
        return buildings.values().stream().filter(b -> b.city.equals(city)).toList();
    }
}
