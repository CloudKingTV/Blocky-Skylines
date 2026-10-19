package com.cloudkingtv.townstead.city;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Server-side state of one town. Mutate only through {@link CityManager} so changes are saved and synced. */
public final class City {
    public static final Codec<City> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(c -> c.id),
            Codec.STRING.fieldOf("name").forGetter(c -> c.name),
            UUIDUtil.CODEC.fieldOf("owner").forGetter(c -> c.owner),
            Codec.STRING.fieldOf("owner_name").forGetter(c -> c.ownerName),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(c -> c.dimension),
            BlockPos.CODEC.fieldOf("town_hall").forGetter(c -> c.townHall),
            TilePos.CODEC.listOf().fieldOf("tiles").forGetter(c -> List.copyOf(c.tiles)),
            Codec.LONG.optionalFieldOf("treasury", 0L).forGetter(c -> c.treasury),
            Codec.LONG.optionalFieldOf("founded", 0L).forGetter(c -> c.foundedGameTime),
            Codec.unboundedMap(Identifier.CODEC, Codec.INT).optionalFieldOf("stock", Map.of()).forGetter(c -> c.stock),
            BlockPos.CODEC.listOf().optionalFieldOf("storehouses", List.of()).forGetter(c -> List.copyOf(c.storehouses)),
            com.cloudkingtv.townstead.economy.Budget.CODEC.optionalFieldOf("budget").forGetter(c -> java.util.Optional.of(c.budget)),
            Codec.INT.optionalFieldOf("milestone", 0).forGetter(c -> c.milestone),
            com.cloudkingtv.townstead.transit.CoachLine.CODEC.listOf().optionalFieldOf("lines", List.of()).forGetter(c -> List.copyOf(c.lines)),
            Codec.INT.optionalFieldOf("next_line", 1).forGetter(c -> c.nextLineId),
            Planning.CODEC.optionalFieldOf("planning", Planning.EMPTY).forGetter(c -> new Planning(List.copyOf(c.districts), c.nextDistrictId, List.copyOf(c.policies)))
    ).apply(i, City::new));

    /** Districts and policies (one field, to keep the codec within its limit). */
    record Planning(List<com.cloudkingtv.townstead.district.District> districts, int nextDistrict,
                    List<com.cloudkingtv.townstead.district.Policy> policies) {
        static final Planning EMPTY = new Planning(List.of(), 1, List.of());
        static final Codec<Planning> CODEC = RecordCodecBuilder.create(i -> i.group(
                com.cloudkingtv.townstead.district.District.CODEC.listOf().optionalFieldOf("districts", List.of()).forGetter(Planning::districts),
                Codec.INT.optionalFieldOf("next_district", 1).forGetter(Planning::nextDistrict),
                com.cloudkingtv.townstead.district.Policy.CODEC.listOf().optionalFieldOf("policies", List.of()).forGetter(Planning::policies)
        ).apply(i, Planning::new));
    }

    final UUID id;
    String name;
    final UUID owner;
    String ownerName;
    final ResourceKey<Level> dimension;
    final BlockPos townHall;
    final Set<TilePos> tiles;
    long treasury;
    final long foundedGameTime;
    /** Building materials in the Town Storehouse (item id to count). Mutate through {@link CityManager}. */
    final Map<Identifier, Integer> stock;
    final Set<BlockPos> storehouses;
    /** Taxes, department budgets, loans and the ledger. The treasury above is in hundredths of an emerald. */
    final com.cloudkingtv.townstead.economy.Budget budget;
    /** The highest milestone reached ({@link com.cloudkingtv.townstead.progress.Milestone} ordinal). */
    int milestone;
    /** Coach lines, in the order they were drawn. Mutate through {@link CityManager}. */
    final List<com.cloudkingtv.townstead.transit.CoachLine> lines;
    int nextLineId;
    /** Districts, in the order they were painted. Mutate through {@link CityManager}. */
    final List<com.cloudkingtv.townstead.district.District> districts;
    int nextDistrictId;
    /** Town-wide policies. */
    final java.util.Set<com.cloudkingtv.townstead.district.Policy> policies;

    City(UUID id, String name, UUID owner, String ownerName, ResourceKey<Level> dimension, BlockPos townHall,
         List<TilePos> tiles, long treasury, long foundedGameTime, Map<Identifier, Integer> stock, List<BlockPos> storehouses,
         java.util.Optional<com.cloudkingtv.townstead.economy.Budget> budget, int milestone,
         List<com.cloudkingtv.townstead.transit.CoachLine> lines, int nextLineId, Planning planning) {
        this.districts = new ArrayList<>(planning.districts());
        this.nextDistrictId = planning.nextDistrict();
        this.policies = java.util.EnumSet.noneOf(com.cloudkingtv.townstead.district.Policy.class);
        this.policies.addAll(planning.policies());
        this.milestone = milestone;
        this.lines = new ArrayList<>(lines);
        this.nextLineId = nextLineId;
        this.budget = budget.orElseGet(com.cloudkingtv.townstead.economy.Budget::new);
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.ownerName = ownerName;
        this.dimension = dimension;
        this.townHall = townHall;
        this.tiles = new LinkedHashSet<>(tiles);
        this.treasury = treasury;
        this.foundedGameTime = foundedGameTime;
        this.stock = new TreeMap<>(stock);
        this.storehouses = new LinkedHashSet<>(storehouses);
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName; }
    public ResourceKey<Level> dimension() { return dimension; }
    public BlockPos townHall() { return townHall; }
    public Set<TilePos> tiles() { return java.util.Collections.unmodifiableSet(tiles); }
    public long treasury() { return treasury; }
    public Map<Identifier, Integer> stock() { return java.util.Collections.unmodifiableMap(stock); }
    public Set<BlockPos> storehouses() { return java.util.Collections.unmodifiableSet(storehouses); }
    /** Read-only use; change it through {@link CityManager#editBudget}. */
    public com.cloudkingtv.townstead.economy.Budget budget() { return budget; }
    public int milestone() { return milestone; }
    public List<com.cloudkingtv.townstead.transit.CoachLine> lines() { return java.util.Collections.unmodifiableList(lines); }
    public List<com.cloudkingtv.townstead.district.District> districts() { return java.util.Collections.unmodifiableList(districts); }
    public java.util.Set<com.cloudkingtv.townstead.district.Policy> policies() { return java.util.Collections.unmodifiableSet(policies); }
    /** Lines, districts and policies as the town's model reads them. */
    public com.cloudkingtv.townstead.district.Civics civics() {
        return new com.cloudkingtv.townstead.district.Civics(lines, districts, policies);
    }

    public boolean contains(BlockPos pos) {
        return tiles.contains(TilePos.of(pos));
    }

    public CitySummary summary() {
        return new CitySummary(id, name, owner, ownerName, dimension, townHall, new ArrayList<>(tiles), treasury, Map.copyOf(stock), budget.copy(), milestone, List.copyOf(lines), List.copyOf(districts), java.util.Set.copyOf(policies));
    }
}
