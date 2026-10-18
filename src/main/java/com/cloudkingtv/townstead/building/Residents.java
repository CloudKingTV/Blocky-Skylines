package com.cloudkingtv.townstead.building;

import com.cloudkingtv.townstead.zone.ZoneType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jspecify.annotations.Nullable;

/**
 * The people of a town, generated deterministically from its buildings so client and server agree without
 * sending them: households with names and ages for every home, and jobs filled from those adults in building
 * order. Phase 4 turns these into simulated citizens; the names stay.
 */
public final class Residents {
    public enum Stage { CHILD, TEEN, ADULT, SENIOR }

    public record Person(String first, String last, int age, Stage stage) {
        public String name() { return first + " " + last; }
    }

    public record Household(String surname, List<Person> members) {}

    /** A filled job: who, where they live, where they work, and their trade (a translation key suffix). */
    public record Job(Person person, int homeId, int workplaceId, String trade) {}

    private static final String[] FIRST = {"Ada", "Alder", "Ansel", "Briar", "Bram", "Clem", "Corin", "Della", "Edda", "Elric", "Fern",
            "Finn", "Gwen", "Hale", "Hazel", "Ivo", "Juniper", "Kit", "Lark", "Linden", "Mabel", "Milo", "Nell", "Oswin", "Pip",
            "Quill", "Rosa", "Rowan", "Sage", "Silas", "Tamsin", "Tobin", "Una", "Vale", "Wren", "Yara", "Ezra", "Ivy", "Otto", "Maren"};
    private static final String[] LAST = {"Miller", "Thatcher", "Cooper", "Fletcher", "Mason", "Smith", "Weaver", "Brewer", "Baker",
            "Carter", "Fisher", "Shepherd", "Tanner", "Turner", "Wright", "Chandler", "Draper", "Potter", "Glover", "Hayward", "Reeve",
            "Ashdown", "Brook", "Fernley", "Hill", "Marsh", "Moss", "Oakley", "Rowe", "Thorne", "Wells", "Bell", "Archer", "Cobb"};
    private static final int[] SIZES = {2, 3, 3, 4, 1, 5, 2, 4}; // averages three

    private static int mix(long seed, int i) {
        long h = seed * 0x9E3779B97F4A7C15L + i * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 31;
        h *= 0x94D049BB133111EBL;
        return (int) ((h ^ (h >>> 29)) & 0x7fffffff);
    }

    public static int householdSize(Building b, int index) {
        return SIZES[mix(b.seed, index) % SIZES.length];
    }

    /** People living in a home (0 for workplaces). */
    public static int people(Building b) {
        if (!b.zone.residential()) return 0;
        int n = 0;
        for (int i = 0; i < b.households; i++) n += householdSize(b, i);
        return n;
    }

    public static List<Household> households(Building b) {
        List<Household> out = new ArrayList<>();
        if (!b.zone.residential()) return out;
        for (int i = 0; i < b.households; i++) {
            int size = householdSize(b, i);
            String last = LAST[mix(b.seed, 100 + i) % LAST.length];
            List<Person> members = new ArrayList<>();
            for (int m = 0; m < size; m++) {
                int r = mix(b.seed, 1000 + i * 16 + m);
                int age = m == 0 ? 22 + r % 40 : m == 1 ? 20 + r % 44 : m == 4 ? 66 + r % 20 : 1 + r % 17;
                Stage stage = age < 13 ? Stage.CHILD : age < 18 ? Stage.TEEN : age < 65 ? Stage.ADULT : Stage.SENIOR;
                members.add(new Person(FIRST[mix(b.seed, 2000 + i * 16 + m) % FIRST.length], last, age, stage));
            }
            out.add(new Household(last, members));
        }
        return out;
    }

    /** A workplace's name, after the family that runs it: "Thatcher's Smithy". */
    public static String ownerName(Building b) {
        return LAST[mix(b.seed, 7) % LAST.length];
    }

    /** Fills every working workplace's jobs with working-age residents, homes and workplaces in id order. */
    public static Map<Integer, List<Job>> employment(Collection<Building> town) {
        List<Building> sorted = new ArrayList<>(town);
        sorted.sort(Comparator.comparingInt(b -> b.id));
        List<Person> adults = new ArrayList<>();
        List<Integer> homes = new ArrayList<>();
        for (Building b : sorted) {
            if (!b.working() || !b.zone.residential()) continue;
            for (Household h : households(b)) {
                for (Person p : h.members()) if (p.stage() == Stage.ADULT) { adults.add(p); homes.add(b.id); }
            }
        }
        Map<Integer, List<Job>> out = new HashMap<>();
        int next = 0;
        for (Building w : sorted) {
            if (!w.working() || w.zone.residential()) continue;
            List<Job> jobs = new ArrayList<>();
            for (int slot = 0; slot < w.jobs && next < adults.size(); slot++, next++) {
                jobs.add(new Job(adults.get(next), homes.get(next), w.id, trade(w, slot)));
            }
            out.put(w.id, jobs);
        }
        return out;
    }

    /** Where each person works, keyed by "homeId:name". */
    public static Map<String, Job> jobsByPerson(Map<Integer, List<Job>> employment) {
        Map<String, Job> out = new HashMap<>();
        employment.values().forEach(list -> list.forEach(j -> out.put(j.homeId() + ":" + j.person().name(), j)));
        return out;
    }

    /** The trade practised at a workplace, from the workstation it was built with (the villager professions). */
    public static String trade(Building w, int slot) {
        return switch (w.zone) {
            case CIVIC -> switch (w.serviceType()) {
                case CLINIC -> "healer";
                case FIRE_STATION -> "firewatch";
                case GUARD_HOUSE -> "guard";
                case SCHOOL -> "teacher";
                case COMPOST_YARD -> "farmer";
                case PARK -> "gardener";
                case WINDMILL, WATERMILL -> "miller";
                case WELL, WATER_TOWER -> "water_keeper";
            };
            case OFFICE -> new String[] {"clerk", "cartographer", "librarian", "scribe"}[slot % 4];
            case INDUSTRIAL -> new String[] {stationTrade(w, Blocks.SMOKER, Blocks.BLAST_FURNACE), "mason", "farmer", "labourer"}[slot % 4];
            case COMMERCIAL_HIGH -> slot == 0 ? "merchant" : "stallholder";
            default -> slot == 0 ? stationTrade(w, null, null) : "shop_hand";
        };
    }

    private static String stationTrade(Building w, @Nullable Block a, @Nullable Block b) {
        for (BuildingGenerator.Step s : BuildingGenerator.plan(w).steps()) {
            Block block = s.state().getBlock();
            if (a != null && (block == a || block == b)) return block == Blocks.SMOKER ? "butcher" : "armorer";
            if (a == null) {
                if (block == Blocks.SMITHING_TABLE) return "toolsmith";
                if (block == Blocks.FLETCHING_TABLE) return "fletcher";
                if (block == Blocks.CARTOGRAPHY_TABLE) return "cartographer";
                if (block == Blocks.LOOM) return "weaver";
                if (block == Blocks.LECTERN) return "librarian";
                if (block == Blocks.BREWING_STAND) return "cleric";
                if (block == Blocks.GRINDSTONE) return "weaponsmith";
            }
        }
        return a == null ? "shopkeeper" : "smith";
    }

    /** Display noun for a workplace from its trade: "Smithy", "Bakery"... as a translation key suffix. */
    public static String placeKind(Building w) {
        if (w.civic()) return "civic." + w.service;
        if (w.zone == ZoneType.OFFICE) return "guild";
        if (w.zone == ZoneType.COMMERCIAL_HIGH) return "market";
        return trade(w, 0);
    }

    private Residents() {}
}
