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
        CONSTRUCTING("constructing"), PAUSED("paused"), ACTIVE("active"), ABANDONED("abandoned");

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
            Codec.INT.optionalFieldOf("setback", 0).forGetter(b -> b.setback),
            Extra.CODEC.optionalFieldOf("extra", Extra.DEFAULT).forGetter(Building::extra)
    ).apply(i, (id, city, zone, cx, cz, front, y, seed, state, progress, hh, jobs, started, needs, setback, extra) -> {
        Building b = new Building(id, city, zone, cx, cz, front, y, seed, started, setback);
        b.level = extra.level();
        b.style = extra.style();
        b.activeSince = extra.activeSince();
        b.trouble = extra.trouble();
        b.owed = extra.owed();
        b.upgrading = extra.upgrading();
        b.repairing = extra.repairing();
        b.service = extra.service();
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
    /** 1 to {@link #MAX_LEVEL}; higher levels rebuild bigger, house more people and employ more. */
    public int level = 1;
    /** Building style (biome palette key), chosen from the lot's biome when the site opens. */
    public String style = "plains";
    /** Game time the current level was finished (for level-up timing). */
    public long activeSince;
    /** Ticks of trouble (no road, no jobs, no customers) piled up; enough of it and the building is abandoned. */
    public int trouble;
    /** Construction steps owed while the site's chunks were unloaded; caught up when they load. */
    public int owed;
    /** True while a finished building is being rebuilt at its next level. */
    public boolean upgrading;
    /** True while an abandoned building is being repaired (an upgrade without a new level). */
    public boolean repairing;
    /** For civic buildings (zone CIVIC): which service or utility this is ({@link ServiceType} key), else empty. */
    public String service = "";

    public static final int MAX_LEVEL = 3;

    /** Bookkeeping that grew after the first save format; saved as one compound. */
    record Extra(int level, String style, long activeSince, int trouble, int owed, boolean upgrading, boolean repairing, String service) {
        static final Extra DEFAULT = new Extra(1, "plains", 0L, 0, 0, false, false, "");
        static final Codec<Extra> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("level", 1).forGetter(Extra::level),
                Codec.STRING.optionalFieldOf("style", "plains").forGetter(Extra::style),
                Codec.LONG.optionalFieldOf("active_since", 0L).forGetter(Extra::activeSince),
                Codec.INT.optionalFieldOf("trouble", 0).forGetter(Extra::trouble),
                Codec.INT.optionalFieldOf("owed", 0).forGetter(Extra::owed),
                Codec.BOOL.optionalFieldOf("upgrading", false).forGetter(Extra::upgrading),
                Codec.BOOL.optionalFieldOf("repairing", false).forGetter(Extra::repairing),
                Codec.STRING.optionalFieldOf("service", "").forGetter(Extra::service)
        ).apply(i, Extra::new));
    }

    Extra extra() {
        return new Extra(level, style, activeSince, trouble, owed, upgrading, repairing, service);
    }

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
        b.level = level;
        b.style = style;
        b.activeSince = activeSince;
        b.trouble = trouble;
        b.owed = owed;
        b.upgrading = upgrading;
        b.repairing = repairing;
        b.service = service;
        return b;
    }

    public boolean civic() {
        return zone == ZoneType.CIVIC;
    }

    public com.cloudkingtv.townstead.service.ServiceType serviceType() {
        return com.cloudkingtv.townstead.service.ServiceType.byKey(service);
    }

    /** Whether the building is lived in / open for business (counts toward population and jobs). */
    public boolean working() {
        return state == State.ACTIVE || (upgrading && state != State.ABANDONED);
    }
}
