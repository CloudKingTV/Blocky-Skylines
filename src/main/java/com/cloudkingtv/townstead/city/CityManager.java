package com.cloudkingtv.townstead.city;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.network.CitySnapshotPayload;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.network.PacketDistributor;

/** All towns on the server. Stored once per world in data/townstead/cities.dat. */
public final class CityManager extends SavedData {
    /** Side length, in tiles, of the square a new town starts with. */
    public static final int STARTING_TILES = 3;
    /** Minimum free tiles between two towns' claims. */
    public static final int TOWN_SPACING_TILES = 2;

    public static final SavedDataType<CityManager> TYPE = new SavedDataType<>(Townstead.id("cities"), CityManager::new,
            RecordCodecBuilder.create(i -> i.group(
                    City.CODEC.listOf().fieldOf("cities").forGetter(m -> List.copyOf(m.cities.values()))
            ).apply(i, CityManager::new)));

    private final Map<UUID, City> cities = new LinkedHashMap<>();

    public CityManager() {}

    private CityManager(List<City> loaded) {
        loaded.forEach(c -> cities.put(c.id, c));
    }

    public static CityManager get(MinecraftServer server) {
        return server.getDataStorage().computeIfAbsent(TYPE);
    }

    public Collection<City> all() {
        return cities.values();
    }

    public Optional<City> byId(UUID id) {
        return Optional.ofNullable(cities.get(id));
    }

    public Optional<City> at(ServerLevel level, BlockPos pos) {
        TilePos tile = TilePos.of(pos);
        return cities.values().stream()
                .filter(c -> c.dimension.equals(level.dimension()) && c.tiles.contains(tile))
                .findFirst();
    }

    public Optional<City> ownedBy(UUID player) {
        return cities.values().stream().filter(c -> c.owner.equals(player)).findFirst();
    }

    public Optional<City> byTownHall(ServerLevel level, BlockPos pos) {
        return cities.values().stream()
                .filter(c -> c.dimension.equals(level.dimension()) && c.townHall.equals(pos))
                .findFirst();
    }

    public enum FoundResult { OK, ALREADY_MAYOR, TOO_CLOSE }

    /** Checks whether a town can be founded with its Town Hall at {@code pos}. */
    public FoundResult canFound(ServerLevel level, BlockPos pos, UUID founder) {
        if (ownedBy(founder).isPresent()) return FoundResult.ALREADY_MAYOR;
        List<TilePos> claim = startingClaim(pos);
        int margin = TOWN_SPACING_TILES;
        for (City other : cities.values()) {
            if (!other.dimension.equals(level.dimension())) continue;
            for (TilePos mine : claim) {
                for (TilePos theirs : other.tiles) {
                    if (Math.abs(mine.x() - theirs.x()) <= margin && Math.abs(mine.z() - theirs.z()) <= margin) {
                        return FoundResult.TOO_CLOSE;
                    }
                }
            }
        }
        return FoundResult.OK;
    }

    public City found(ServerLevel level, BlockPos pos, ServerPlayer founder) {
        String name = founder.getGameProfile().name() + "'s Town";
        City city = new City(UUID.randomUUID(), name, founder.getUUID(), founder.getGameProfile().name(),
                level.dimension(), pos.immutable(), startingClaim(pos), 0L, level.getGameTime());
        cities.put(city.id, city);
        changed(level.getServer());
        Townstead.LOGGER.info("Founded town '{}' at {} for {}", name, pos, founder.getGameProfile().name());
        return city;
    }

    public void disband(MinecraftServer server, City city) {
        if (cities.remove(city.id) != null) changed(server);
    }

    private static List<TilePos> startingClaim(BlockPos hall) {
        TilePos center = TilePos.of(hall);
        int r = STARTING_TILES / 2;
        List<TilePos> tiles = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) tiles.add(center.offset(dx, dz));
        return tiles;
    }

    private void changed(MinecraftServer server) {
        setDirty();
        PacketDistributor.sendToAllPlayers(snapshot());
    }

    public CitySnapshotPayload snapshot() {
        return new CitySnapshotPayload(cities.values().stream().map(City::summary).toList());
    }
}
