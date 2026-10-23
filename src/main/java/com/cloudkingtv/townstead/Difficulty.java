package com.cloudkingtv.townstead;

import net.minecraft.network.chat.Component;

/**
 * One server setting for how hard towns are to run. Each preset scales what the town earns in taxes, what it pays
 * for upkeep, land and imports, how much demand there is, how often fires start, and how fast buildings go up and
 * level up. Normal is the balance the autoplayer was tuned to.
 */
public enum Difficulty {
    RELAXED(1.25, 0.8, 2, 10, 0.5, 1.3),
    NORMAL(1.0, 1.0, 3, 0, 1.0, 1.0),
    HARD(0.85, 1.2, 4, -10, 1.5, 0.8);

    /** Tax income multiplier. */
    public final double taxes;
    /** Upkeep (services, roads, coaches, policies) and land price multiplier. */
    public final double costs;
    /** Imports cost this many times a material's selling price. */
    public final int importMarkup;
    /** Added to every demand bar. */
    public final int demand;
    /** Fire rate multiplier. */
    public final double fires;
    /** Construction and levelling pace multiplier. */
    public final double building;

    Difficulty(double taxes, double costs, int importMarkup, int demand, double fires, double building) {
        this.taxes = taxes;
        this.costs = costs;
        this.importMarkup = importMarkup;
        this.demand = demand;
        this.fires = fires;
        this.building = building;
    }

    public static Difficulty current() {
        try {
            return TownsteadConfig.DIFFICULTY.get();
        } catch (IllegalStateException notLoaded) {
            return NORMAL; // before the synced config arrives
        }
    }

    public Component displayName() { return Component.translatable("difficulty.townstead." + name().toLowerCase(java.util.Locale.ROOT)); }
}
