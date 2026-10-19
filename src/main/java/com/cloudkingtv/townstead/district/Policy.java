package com.cloudkingtv.townstead.district;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Policies, as in Cities: Skylines: rules the mayor sets for the whole town or for one district, each with a plain
 * effect and (for some) a daily cost. Where they bite is spread through the model: fields, happiness, demand,
 * taxes, levels and the coaches.
 */
public enum Policy implements StringRepresentable {
    /** Smoke hoods on every forge: workshops pollute half as much. Costs 3 hundredths a day per workshop job. */
    CLEAN_HEARTHS("clean_hearths", Items.CAMPFIRE, true),
    /** Shops and workshops close early: noise from them and the roads falls by 40%, shops pay 10% less tax. */
    QUIET_NIGHTS("quiet_nights", Items.NOTE_BLOCK, true),
    /** Weekly markets: shops always have customers and the town wants more of them, but they're louder. */
    MARKET_DAYS("market_days", Items.EMERALD, true),
    /** Lanterns along every road: land value +6. Costs half a hundredth a day per block of road. */
    LANTERN_STREETS("lantern_streets", Items.LANTERN, true),
    /** Window boxes and hanging baskets: homes +3 happiness, land value +3. Costs 1 hundredth a day per household. */
    GARDEN_TOWN("garden_town", Items.POPPY, true),
    /** Old Town charm: no building grows past level 2, but land value +8 and homes +3 happiness. */
    OLD_TOWN("old_town", Items.MOSSY_COBBLESTONE, true),
    /** Craftsmen's relief: workshops pay half tax and the town wants more of them. */
    CRAFT_RELIEF("craft_relief", Items.SMITHING_TABLE, true),
    /** Evening classes: everyone counts as having a school in reach. Costs 2 hundredths a day per adult. */
    EVENING_CLASSES("evening_classes", Items.BOOK, true),
    /** Free coaches: no fares, and 40% more commuters by a line ride it. Town-wide only. */
    FREE_COACHES("free_coaches", Items.MINECART, false);

    public static final Codec<Policy> CODEC = StringRepresentable.fromEnum(Policy::values);

    private final String key;
    private final Item icon;
    /** Whether a district can set it (otherwise only the whole town). */
    public final boolean local;

    Policy(String key, Item icon, boolean local) {
        this.key = key;
        this.icon = icon;
        this.local = local;
    }

    public ItemStack icon() { return new ItemStack(icon); }
    public Component displayName() { return Component.translatable("policy.townstead." + key); }
    public Component description() { return Component.translatable("policy.townstead." + key + ".desc"); }

    @Override
    public String getSerializedName() { return key; }
}
