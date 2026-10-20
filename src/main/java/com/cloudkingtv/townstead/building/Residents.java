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
 * order. Each person has an education level (0 unschooled to 3 highly educated) from their seed; a school in reach
 * of their home lifts it by one. Jobs that need education (offices, the clinic, the school) go to those who have it.
 */
public final class Residents {
    public enum Stage { CHILD, TEEN, ADULT, SENIOR }

    /** {@code education}: 0 unschooled, 1 schooled, 2 well educated, 3 highly educated (before any school in reach). */
    public record Person(String first, String last, int age, Stage stage, int education) {
        public String name() { return first + " " + last; }

        /** Education counting a school in reach of home: children go to it, adults take its evening classes. */
        public int education(boolean schooled) {
            return schooled ? Math.min(MAX_EDUCATION, education + 1) : education;
        }
    }

    public static final int MAX_EDUCATION = 3;
    /** Education a well-paid job asks for. */
    public static final int SKILLED = 2;

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
                int e = mix(b.seed, 3000 + i * 16 + m) % 20;
                int education = switch (stage) {
                    case CHILD -> 0;
                    case TEEN -> 1;
                    default -> e < 6 ? 0 : e < 15 ? 1 : e < 19 ? 2 : 3; // 30% / 45% / 20% / 5%
                };
                members.add(new Person(FIRST[mix(b.seed, 2000 + i * 16 + m) % FIRST.length], last, age, stage, education));
            }
            out.add(new Household(last, members));
        }
        return out;
    }

    /** A workplace's name, after the family that runs it: "Thatcher's Smithy". */
    public static String ownerName(Building b) {
        return LAST[mix(b.seed, 7) % LAST.length];
    }

    /** Fills every working workplace's jobs with working-age residents (no school counted). */
    public static Map<Integer, List<Job>> employment(Collection<Building> town) {
        return employment(town, java.util.Set.of());
    }

    /**
     * Fills every working workplace's jobs with working-age residents. Skilled jobs are filled first, from the
     * educated; then the rest, homes and workplaces in id order, so the result is the same on client and server.
     * {@code schooled}: ids of homes with a school in reach.
     */
    public static Map<Integer, List<Job>> employment(Collection<Building> town, java.util.Set<Integer> schooled) {
        List<Building> sorted = new ArrayList<>(town);
        sorted.sort(Comparator.comparingInt(b -> b.id));
        List<Person> adults = new ArrayList<>();
        List<Integer> homes = new ArrayList<>();
        List<Integer> edu = new ArrayList<>();
        for (Building b : sorted) {
            if (!b.working() || !b.zone.residential()) continue;
            boolean school = schooled.contains(b.id);
            for (Household h : households(b)) {
                for (Person p : h.members()) {
                    if (p.stage() != Stage.ADULT) continue;
                    adults.add(p);
                    homes.add(b.id);
                    edu.add(p.education(school));
                }
            }
        }
        boolean[] taken = new boolean[adults.size()];
        Map<Integer, Job[]> filled = new HashMap<>();
        for (int need = MAX_EDUCATION; need >= 0; need--) {
            int next = 0;
            for (Building w : sorted) {
                if (!w.working() || w.zone.residential()) continue;
                Job[] slots = filled.computeIfAbsent(w.id, k -> new Job[w.jobs]);
                for (int slot = 0; slot < w.jobs; slot++) {
                    if (slots[slot] != null || required(w, slot) != need) continue;
                    while (next < adults.size() && (taken[next] || edu.get(next) < need)) next++;
                    if (next >= adults.size()) break;
                    taken[next] = true;
                    slots[slot] = new Job(adults.get(next), homes.get(next), w.id, trade(w, slot));
                }
            }
        }
        Map<Integer, List<Job>> out = new HashMap<>();
        for (Building w : sorted) {
            Job[] slots = filled.get(w.id);
            if (slots == null) continue;
            List<Job> jobs = new ArrayList<>();
            for (Job j : slots) if (j != null) jobs.add(j);
            out.put(w.id, jobs);
        }
        return out;
    }

    /** Education a job slot asks for: offices and the clinic and school want the well educated, a market's merchant some schooling. */
    public static int required(Building w, int slot) {
        return switch (w.zone) {
            case OFFICE -> SKILLED;
            case CIVIC -> w.serviceType() == com.cloudkingtv.townstead.service.ServiceType.CLINIC
                    || w.serviceType() == com.cloudkingtv.townstead.service.ServiceType.SCHOOL ? SKILLED : 0;
            case COMMERCIAL_HIGH -> slot == 0 ? 1 : 0;
            default -> 0;
        };
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
                case FOUNDERS_FOUNTAIN, CLOCK_TOWER, CHAPEL -> slot == 0 ? "keeper" : "gardener";
                case COACH_HOUSE -> slot == 0 ? "ostler" : "coachman";
                case GREAT_LIBRARY -> new String[] {"librarian", "scribe", "teacher", "keeper"}[slot % 4];
                case CATHEDRAL -> new String[] {"cleric", "cleric", "keeper", "gardener"}[slot % 4];
                case CASTLE_KEEP -> new String[] {"guard", "guard", "firewatch", "armorer", "smith"}[slot % 5];
            };
            case OFFICE -> new String[] {"clerk", "cartographer", "librarian", "scribe"}[slot % 4];
            case INDUSTRIAL -> switch (w.spec) {
                case "farming" -> new String[] {"farmer", "farmer", "shepherd", "labourer"}[slot % 4];
                case "forestry" -> new String[] {"woodcutter", "woodcutter", "sawyer", "labourer"}[slot % 4];
                default -> new String[] {stationTrade(w, Blocks.SMOKER, Blocks.BLAST_FURNACE), "mason", "farmer", "labourer"}[slot % 4];
            };
            case COMMERCIAL, COMMERCIAL_HIGH -> "leisure".equals(w.spec) ? (slot == 0 ? "innkeeper" : "barkeep")
                    : w.zone == ZoneType.COMMERCIAL_HIGH ? (slot == 0 ? "merchant" : "stallholder")
                    : slot == 0 ? stationTrade(w, null, null) : "shop_hand";
            default -> slot == 0 ? stationTrade(w, null, null) : "shop_hand";
        };
    }

    /** Workstation blocks per building (id, level, seed): reading them means generating the plan, so it's kept. */
    private static final Map<String, List<Block>> STATIONS = new java.util.concurrent.ConcurrentHashMap<>();

    private static List<Block> stations(Building w) {
        if (STATIONS.size() > 4096) STATIONS.clear();
        return STATIONS.computeIfAbsent(w.id + ":" + w.level + ":" + w.seed + ":" + w.style, k -> {
            List<Block> out = new ArrayList<>();
            for (BuildingGenerator.Step s : BuildingGenerator.plan(w).steps()) {
                Block block = s.state().getBlock();
                if (block == Blocks.SMOKER || block == Blocks.BLAST_FURNACE || block == Blocks.SMITHING_TABLE || block == Blocks.FLETCHING_TABLE
                        || block == Blocks.CARTOGRAPHY_TABLE || block == Blocks.LOOM || block == Blocks.LECTERN || block == Blocks.BREWING_STAND
                        || block == Blocks.GRINDSTONE) out.add(block);
            }
            return out;
        });
    }

    private static String stationTrade(Building w, @Nullable Block a, @Nullable Block b) {
        for (Block block : stations(w)) {
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
