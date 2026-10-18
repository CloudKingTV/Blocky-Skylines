package com.cloudkingtv.townstead.zone;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What a zone cell grows. Colours follow Cities: Skylines: light/dark green homes, light/dark blue shops, yellow
 * industry, cyan offices. High density and offices only grow once the town is big enough ({@link #minPopulation}).
 */
public enum ZoneType implements StringRepresentable {
    RESIDENTIAL("residential", 0x5BD35B, Items.OAK_DOOR, Need.HOMES, 0),
    RESIDENTIAL_HIGH("residential_high", 0x2E9E3A, Items.BRICKS, Need.HOMES, 30),
    COMMERCIAL("commercial", 0x4FA3FF, Items.BARREL, Need.SHOPS, 0),
    COMMERCIAL_HIGH("commercial_high", 0x2E62C9, Items.BELL, Need.SHOPS, 30),
    INDUSTRIAL("industrial", 0xF2C744, Items.HAY_BLOCK, Need.WORKSHOPS, 0),
    OFFICE("office", 0x3FD3D3, Items.LECTERN, Need.OFFICES, 40),
    /** Not painted: services and utilities placed from the planner. */
    CIVIC("civic", 0xE8E8E8, Items.BELL, Need.CIVIC, 0);

    /** The zones the zoning tool paints, in brush order. */
    public static final java.util.List<ZoneType> PAINTABLE = java.util.Arrays.stream(values()).filter(z -> z != CIVIC).toList();

    /** The four demand bars (R, C, I, O); each zone answers one of them. */
    public enum Need { HOMES, SHOPS, WORKSHOPS, OFFICES, CIVIC }

    public static final Codec<ZoneType> CODEC = StringRepresentable.fromEnum(ZoneType::values);

    private final String key;
    public final int rgb;
    private final Item icon;
    public final Need need;
    /** Population the town needs before this zone grows. */
    public final int minPopulation;

    ZoneType(String key, int rgb, Item icon, Need need, int minPopulation) {
        this.key = key;
        this.rgb = rgb;
        this.icon = icon;
        this.need = need;
        this.minPopulation = minPopulation;
    }

    public ItemStack icon() { return new ItemStack(icon); }
    public Component displayName() { return Component.translatable("zone.townstead." + key); }
    public boolean highDensity() { return this == RESIDENTIAL_HIGH || this == COMMERCIAL_HIGH; }
    public boolean residential() { return need == Need.HOMES; }

    @Override
    public String getSerializedName() { return key; }
}
