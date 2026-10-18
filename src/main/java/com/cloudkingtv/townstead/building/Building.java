package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.road.Material;
import com.cloudkingtv.townstead.zone.ZoneType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.StringRepresentable;
import org.jspecify.annotations.Nullable;

/**
 * A growable building on one zone cell. Its blocks aren't stored: {@link BuildingGenerator} rebuilds the same plan
 * from the zone, cell, frontage, base height and seed, and {@code progress} counts how many plan steps are in the
 * world. The real blocks in the world are the building; this record is the bookkeeping beside them.
 */
public final class Building {
    public enum State implements StringRepresentable {
        CONSTRUCTING("constructing"), PAUSED("paused"), ACTIVE("active");

        public static final Codec<State> CODEC = StringRepresentable.fromEnum(State::values);
        private final String key;

        State(String key) { this.key = key; }

        @Override
        public String getSerializedName() { return key; }
    }

    public static final Codec<Building> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("id").forGetter(b -> b.id),
            UUIDUtil.CODEC.fieldOf("city").forGetter(b -> b.city),
            ZoneType.CODEC.fieldOf("zone").forGetter(b -> b.zone),
            Codec.INT.fieldOf("cell_x").forGetter(b -> b.cellX),
            Codec.INT.fieldOf("cell_z").forGetter(b -> b.cellZ),
            Direction.CODEC.fieldOf("front").forGetter(b -> b.front),
            Codec.INT.fieldOf("base_y").forGetter(b -> b.baseY),
            Codec.LONG.fieldOf("seed").forGetter(b -> b.seed),
            State.CODEC.fieldOf("state").forGetter(b -> b.state),
            Codec.INT.optionalFieldOf("progress", 0).forGetter(b -> b.progress),
            Codec.INT.optionalFieldOf("households", 0).forGetter(b -> b.households),
            Codec.INT.optionalFieldOf("jobs", 0).forGetter(b -> b.jobs),
            Codec.LONG.optionalFieldOf("started", 0L).forGetter(b -> b.started),
            Codec.STRING.optionalFieldOf("needs").forGetter(b -> Optional.ofNullable(b.needs).map(Material::name)),
            Codec.INT.optionalFieldOf("setback", 0).forGetter(b -> b.setback)
    ).apply(i, (id, city, zone, cx, cz, front, y, seed, state, progress, hh, jobs, started, needs, setback) -> {
        Building b = new Building(id, city, zone, cx, cz, front, y, seed, started, setback);
        b.state = state;
        b.progress = progress;
        b.households = hh;
        b.jobs = jobs;
        b.needs = needs.flatMap(n -> { try { return Optional.of(Material.valueOf(n)); } catch (IllegalArgumentException e) { return Optional.empty(); } }).orElse(null);
        return b;
    }));

    public final int id;
    public final UUID city;
    public final ZoneType zone;
    public final int cellX, cellZ;
    public final Direction front;
    public final int baseY;
    public final long seed;
    public final long started;
    /** Rows of the lot's front taken by road; the building stands behind them. */
    public final int setback;
    public State state = State.CONSTRUCTING;
    public int progress;
    public int households, jobs;
    /** What a paused site is waiting for. */
    public @Nullable Material needs;

    public Building(int id, UUID city, ZoneType zone, int cellX, int cellZ, Direction front, int baseY, long seed, long started, int setback) {
        this.id = id;
        this.city = city;
        this.zone = zone;
        this.cellX = cellX;
        this.cellZ = cellZ;
        this.front = front;
        this.baseY = baseY;
        this.seed = seed;
        this.started = started;
        this.setback = setback;
    }

    public long cellKey() {
        return com.cloudkingtv.townstead.zone.ZoneRules.cellKey(cellX, cellZ);
    }

    public Building copy() {
        Building b = new Building(id, city, zone, cellX, cellZ, front, baseY, seed, started, setback);
        b.state = state;
        b.progress = progress;
        b.households = households;
        b.jobs = jobs;
        b.needs = needs;
        return b;
    }
}
