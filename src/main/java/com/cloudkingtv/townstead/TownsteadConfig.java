package com.cloudkingtv.townstead;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class TownsteadConfig {
    private static final ModConfigSpec.Builder CLIENT = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue PLANNER_PAN_SPEED = CLIENT
            .comment("Planner camera pan speed multiplier")
            .translation("townstead.configuration.plannerPanSpeed")
            .defineInRange("plannerPanSpeed", 1.0, 0.25, 3.0);
    public static final ModConfigSpec.BooleanValue REDUCE_MOTION = CLIENT
            .comment("Shorter, gentler planner camera transitions")
            .translation("townstead.configuration.reduceMotion")
            .define("reduceMotion", false);

    public static final ModConfigSpec.BooleanValue EDGE_SCROLL = CLIENT
            .comment("Pan the planner camera when the cursor touches the screen edge")
            .translation("townstead.configuration.edgeScroll")
            .define("edgeScroll", false);

    public static final ModConfigSpec.BooleanValue SHOW_ADVISOR = CLIENT
            .comment("Show the town advisor's tips in the planner (toggle with ? in the planner)")
            .translation("townstead.configuration.showAdvisor")
            .define("showAdvisor", true);

    public static final ModConfigSpec CLIENT_SPEC = CLIENT.build();

    private static final ModConfigSpec.Builder SERVER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FREE_BUILD = SERVER
            .comment("Build roads and buildings without materials from the Town Storehouse (creative-mode players always build free)")
            .translation("townstead.configuration.freeBuild")
            .define("freeBuild", false);

    public static final ModConfigSpec.DoubleValue GROWTH_SPEED = SERVER
            .comment("How fast towns build, level up and decline (1 = five blocks a second, levels after 5-10 minutes)")
            .translation("townstead.configuration.growthSpeed")
            .defineInRange("growthSpeed", 1.0, 0.25, 20.0);

    public static final ModConfigSpec.BooleanValue UNLOCK_ALL = SERVER
            .comment("Everything is available from the start, whatever milestone a town has reached (for builders and testing)")
            .translation("townstead.configuration.unlockAll")
            .define("unlockAll", false);

    public static final ModConfigSpec.BooleanValue FIRES = SERVER
            .comment("Buildings now and then catch fire; a fire station's brigade puts them out, unattended ones burn down")
            .translation("townstead.configuration.fires")
            .define("fires", true);

    public static final ModConfigSpec.EnumValue<Difficulty> DIFFICULTY = SERVER
            .comment("Relaxed: more taxes, cheaper upkeep and imports, more demand, fewer fires, faster building. Hard: the reverse")
            .translation("townstead.configuration.difficulty")
            .defineEnum("difficulty", Difficulty.NORMAL);

    public static final ModConfigSpec SERVER_SPEC = SERVER.build();

    private TownsteadConfig() {}
}
