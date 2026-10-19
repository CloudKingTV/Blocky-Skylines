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
                level.dimension(), pos.immutable(), startingClaim(pos), com.cloudkingtv.townstead.economy.TownEconomy.FOUNDING_PURSE,
                level.getGameTime(), Map.of(), List.of(), java.util.Optional.empty(), 0, List.of(), 1);
        cities.put(city.id, city);
        changed(level.getServer());
        Townstead.LOGGER.info("Founded town '{}' at {} for {}", name, pos, founder.getGameProfile().name());
        return city;
    }

    public static final int MAX_NAME_LENGTH = 32;

    /** Trims, drops formatting codes and control characters; empty or over-long names are rejected. */
    public static Optional<String> sanitizeName(String raw) {
        StringBuilder out = new StringBuilder();
        raw.codePoints()
                .filter(cp -> cp != '\u00a7' && !Character.isISOControl(cp))
                .forEach(cp -> out.appendCodePoint(Character.isWhitespace(cp) ? ' ' : cp));
        String name = out.toString().trim().replaceAll(" {2,}", " ");
        int length = name.codePointCount(0, name.length());
        return length == 0 || length > MAX_NAME_LENGTH ? Optional.empty() : Optional.of(name);
    }

    public void rename(MinecraftServer server, City city, String name) {
        city.name = name;
        changed(server);
    }

    /** Applies a change to a town's stock (and storehouse list) then saves and syncs. */
    public void editStock(MinecraftServer server, City city, java.util.function.Consumer<Map<net.minecraft.resources.Identifier, Integer>> edit) {
        edit.accept(city.stock);
        changed(server);
    }

    private boolean pendingSync;

    /** Like {@link #editStock} but syncs on the next {@link #flush} (construction spends a block at a time). */
    public void editStockQuiet(City city, java.util.function.Consumer<Map<net.minecraft.resources.Identifier, Integer>> edit) {
        edit.accept(city.stock);
        setDirty();
        pendingSync = true;
    }

    public void flush(MinecraftServer server) {
        if (pendingSync) changed(server);
    }

    /** Changes a town's taxes, budgets or loans, then saves and syncs. */
    public void editBudget(MinecraftServer server, City city, java.util.function.Consumer<com.cloudkingtv.townstead.economy.Budget> edit) {
        edit.accept(city.budget);
        changed(server);
    }

    /** Records a milestone reached and pays its reward (cents) into the treasury. */
    public void reachMilestone(MinecraftServer server, City city, int milestone, long reward) {
        city.milestone = milestone;
        city.treasury += reward;
        changed(server);
    }

    /** Adds a coach line, giving it the next id; returns that id. */
    public int addLine(MinecraftServer server, City city, com.cloudkingtv.townstead.transit.CoachLine line) {
        int id = city.nextLineId++;
        city.lines.add(new com.cloudkingtv.townstead.transit.CoachLine(id, line.name(), line.color(), line.stops()));
        changed(server);
        return id;
    }

    /** Removes a coach line; returns it, or null if there was none with that id. */
    public com.cloudkingtv.townstead.transit.@org.jspecify.annotations.Nullable CoachLine removeLine(MinecraftServer server, City city, int id) {
        for (var it = city.lines.iterator(); it.hasNext(); ) {
            var line = it.next();
            if (line.id() == id) { it.remove(); changed(server); return line; }
        }
        return null;
    }

    /** Adds a tile to a town's land, paying {@code cost} cents from its treasury. */
    public void buyTile(MinecraftServer server, City city, TilePos tile, long cost) {
        if (city.tiles.add(tile)) city.treasury -= cost;
        changed(server);
    }

    /** Whether a tile is claimed by any town in the dimension, or lies too close to another town's land. */
    public boolean blockedForBuying(ServerLevel level, City buyer, TilePos tile) {
        for (City other : cities.values()) {
            if (!other.dimension.equals(level.dimension())) continue;
            if (other.tiles.contains(tile)) return true;
            if (other == buyer) continue;
            for (TilePos theirs : other.tiles) {
                if (Math.abs(tile.x() - theirs.x()) <= TOWN_SPACING_TILES && Math.abs(tile.z() - theirs.z()) <= TOWN_SPACING_TILES) return true;
            }
        }
        return false;
    }

    /** One settlement: moves the treasury by {@code cents} and updates the budget's records, with a single sync. */
    public void settle(MinecraftServer server, City city, long cents, java.util.function.Consumer<com.cloudkingtv.townstead.economy.Budget> edit) {
        city.treasury += cents;
        edit.accept(city.budget);
        changed(server);
    }

    /** Adds to (or, negative, takes from) the treasury, in cents, then saves and syncs. */
    public void addTreasury(MinecraftServer server, City city, long cents) {
        city.treasury += cents;
        changed(server);
    }

    public void addStorehouse(MinecraftServer server, City city, BlockPos pos) {
        if (city.storehouses.add(pos.immutable())) changed(server);
    }

    public void removeStorehouse(MinecraftServer server, BlockPos pos) {
        for (City c : cities.values()) if (c.storehouses.remove(pos)) changed(server);
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
        pendingSync = false;
        setDirty();
        PacketDistributor.sendToAllPlayers(snapshot());
    }

    public CitySnapshotPayload snapshot() {
        return new CitySnapshotPayload(cities.values().stream().map(City::summary).toList());
    }
}
