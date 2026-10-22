package com.cloudkingtv.townstead.district;

import com.cloudkingtv.townstead.zone.ZoneType;
import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What a district's workshops or shops specialise in, like Cities: Skylines' industry and commercial
 * specialisations: farming, forestry and quarrying for industry, leisure (taverns and inns) for shops. Buildings started in the
 * district grow in that trade's shape and play by its numbers.
 */
public enum Specialization implements StringRepresentable {
    NONE("none", Items.BARRIER, null),
    /** Industry as farms: fields, a barn and a granary. Almost no pollution, many unskilled jobs. */
    FARMING("farming", Items.WHEAT, ZoneType.Need.WORKSHOPS),
    /** Industry as lumber yards: log stacks, a saw shed, replanting. Some pollution, steady jobs. */
    FORESTRY("forestry", Items.OAK_LOG, ZoneType.Need.WORKSHOPS),
    /** Shops as taverns and inns with beer gardens: louder, but they pay more tax. */
    LEISURE("leisure", Items.HONEY_BOTTLE, ZoneType.Need.SHOPS),
    /** Industry as quarries: terraced pits, a crane, a stone-cutting shed. Dusty and loud, like heavy industry. */
    QUARRY("quarry", Items.COBBLESTONE, ZoneType.Need.WORKSHOPS);

    public static final Codec<Specialization> CODEC = StringRepresentable.fromEnum(Specialization::values);

    private final String key;
    private final Item icon;
    /** The kind of lot it changes (null for none). */
    public final ZoneType.@org.jspecify.annotations.Nullable Need need;

    Specialization(String key, Item icon, ZoneType.@org.jspecify.annotations.Nullable Need need) {
        this.key = key;
        this.icon = icon;
        this.need = need;
    }

    public String key() { return key; }
    public ItemStack icon() { return new ItemStack(icon); }
    public Component displayName() { return Component.translatable("specialization.townstead." + key); }

    public static Specialization byKey(String key) {
        for (Specialization s : values()) if (s.key.equals(key)) return s;
        return NONE;
    }

    @Override
    public String getSerializedName() { return key; }
}
