package com.cloudkingtv.townstead.service;

import com.mojang.serialization.Codec;
import java.util.Arrays;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Buildings the mayor places: utilities (power and water, carried along the roads) and services (which cover
 * everything within their radius). Village-flavoured stand-ins for Cities: Skylines' power plants, water pumps,
 * clinics, fire and police stations, schools, landfills and parks.
 */
public enum ServiceType implements StringRepresentable {
    WINDMILL("windmill", Kind.POWER, Items.GRINDSTONE, 60, 0, 2, Department.POWER, 300),
    WATERMILL("watermill", Kind.POWER, Items.OAK_TRAPDOOR, 160, 0, 3, Department.POWER, 500),
    WELL("well", Kind.WATER, Items.WATER_BUCKET, 40, 0, 0, Department.WATER, 150),
    WATER_TOWER("water_tower", Kind.WATER, Items.BARREL, 180, 0, 1, Department.WATER, 450),
    CLINIC("clinic", Kind.SERVICE, Items.BREWING_STAND, 0, 48, 4, Department.HEALTH, 500),
    FIRE_STATION("fire_station", Kind.SERVICE, Items.CAULDRON, 0, 56, 5, Department.FIRE, 500),
    GUARD_HOUSE("guard_house", Kind.SERVICE, Items.IRON_SWORD, 0, 56, 4, Department.POLICE, 450),
    SCHOOL("school", Kind.SERVICE, Items.BOOKSHELF, 0, 64, 5, Department.EDUCATION, 600),
    COMPOST_YARD("compost_yard", Kind.SERVICE, Items.COMPOSTER, 0, 80, 3, Department.GARBAGE, 350),
    PARK("park", Kind.SERVICE, Items.OAK_SAPLING, 0, 32, 1, Department.PARKS, 150),
    // Landmarks: one of each per town, unlocked by milestones; they lift land value and spirits around them.
    FOUNDERS_FOUNTAIN("founders_fountain", Kind.SERVICE, Items.HEART_OF_THE_SEA, 0, 48, 1, Department.PARKS, 200),
    CLOCK_TOWER("clock_tower", Kind.SERVICE, Items.CLOCK, 0, 80, 2, Department.PARKS, 400),
    CHAPEL("chapel", Kind.SERVICE, Items.BELL, 0, 64, 2, Department.PARKS, 300),
    // Transport: the coach house keeps the horses and coaches that run the town's coach lines.
    COACH_HOUSE("coach_house", Kind.TRANSIT, Items.SADDLE, 0, 0, 4, Department.TRANSPORT, 300),
    // Monuments: unique, huge, expensive, and felt across the whole town.
    GREAT_LIBRARY("great_library", Kind.SERVICE, Items.ENCHANTING_TABLE, 0, 140, 10, Department.EDUCATION, 1000),
    CATHEDRAL("cathedral", Kind.SERVICE, Items.END_ROD, 0, 180, 12, Department.PARKS, 1500),
    CASTLE_KEEP("castle_keep", Kind.SERVICE, Items.SHIELD, 0, 200, 20, Department.POLICE, 2000);

    public enum Kind { POWER, WATER, SERVICE, TRANSIT }

    /** Budget lines: each has one slider, shared by the buildings in it. */
    public enum Department {
        POWER(Items.REDSTONE), WATER(Items.WATER_BUCKET), HEALTH(Items.BREWING_STAND), FIRE(Items.CAULDRON),
        POLICE(Items.IRON_SWORD), EDUCATION(Items.BOOKSHELF), GARBAGE(Items.COMPOSTER), PARKS(Items.OAK_SAPLING),
        TRANSPORT(Items.MINECART);

        private final Item icon;

        Department(Item icon) { this.icon = icon; }

        public ItemStack icon() { return new ItemStack(icon); }
        public String key() { return name().toLowerCase(java.util.Locale.ROOT); }
        public Component displayName() { return Component.translatable("department.townstead." + key()); }
    }

    public static final Codec<ServiceType> CODEC = StringRepresentable.fromEnum(ServiceType::values);
    public static final List<ServiceType> UTILITIES = Arrays.stream(values()).filter(s -> s.kind == Kind.POWER || s.kind == Kind.WATER).toList();
    public static final List<ServiceType> SERVICES = Arrays.stream(values()).filter(s -> s.kind == Kind.SERVICE && !s.landmark()).toList();
    public static final List<ServiceType> LANDMARKS = Arrays.stream(values()).filter(ServiceType::landmark).toList();

    private final String key;
    public final Kind kind;
    private final Item icon;
    /** Power or water units supplied (utilities). */
    public final int capacity;
    /** Coverage radius in blocks (services). */
    public final int radius;
    public final int jobs;
    public final Department department;
    /** Running cost at a 100% budget, in hundredths of an emerald per day. */
    public final int upkeep;
    /** Lot size in cells: along the road and back from it. */
    public final int wide, deep;

    ServiceType(String key, Kind kind, Item icon, int capacity, int radius, int jobs, Department department, int upkeep) {
        this.department = department;
        this.upkeep = upkeep;
        int[] lot = switch (key) {
            case "watermill", "clinic", "fire_station", "compost_yard", "park", "founders_fountain", "coach_house" -> new int[] {2, 1};
            case "school", "chapel", "great_library" -> new int[] {2, 2};
            case "cathedral", "castle_keep" -> new int[] {3, 3};
            default -> new int[] {1, 1};
        };
        this.wide = lot[0];
        this.deep = lot[1];
        this.key = key;
        this.kind = kind;
        this.icon = icon;
        this.capacity = capacity;
        this.radius = radius;
        this.jobs = jobs;
    }

    public ItemStack icon() { return new ItemStack(icon); }

    /** A unique building: one per town, placed from the landmarks tool. */
    public boolean landmark() { return this == FOUNDERS_FOUNTAIN || this == CLOCK_TOWER || this == CHAPEL || monument(); }

    /**
     * A monument: the grandest landmarks, unlocked at the city milestones. The Great Library schools every home in
     * its reach, the Cathedral draws pilgrims who fill the shops, and the Castle Keep's garrison guards the town and
     * fights its fires.
     */
    public boolean monument() { return this == GREAT_LIBRARY || this == CATHEDRAL || this == CASTLE_KEEP; }
    public Component displayName() { return Component.translatable("service.townstead." + key); }
    public String key() { return key; }

    @Override
    public String getSerializedName() { return key; }

    /** The type with this key, or null. */
    public static @org.jspecify.annotations.Nullable ServiceType find(String key) {
        for (ServiceType s : values()) if (s.key.equals(key)) return s;
        return null;
    }

    public static ServiceType byKey(String key) {
        for (ServiceType s : values()) if (s.key.equals(key)) return s;
        return PARK;
    }
}
