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

    public static final ModConfigSpec CLIENT_SPEC = CLIENT.build();

    private TownsteadConfig() {}
}
