package com.cloudkingtv.townstead.zone;

import com.mojang.serialization.Codec;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** What a zone cell grows. Colours follow Cities: Skylines (green homes, blue shops, yellow industry). */
public enum ZoneType implements StringRepresentable {
    RESIDENTIAL("residential", 0x5BD35B, Items.OAK_DOOR),
    COMMERCIAL("commercial", 0x4FA3FF, Items.BARREL),
    INDUSTRIAL("industrial", 0xF2C744, Items.HAY_BLOCK);

    public static final Codec<ZoneType> CODEC = StringRepresentable.fromEnum(ZoneType::values);

    private final String key;
    public final int rgb;
    private final Item icon;

    ZoneType(String key, int rgb, Item icon) {
        this.key = key;
        this.rgb = rgb;
        this.icon = icon;
    }

    public ItemStack icon() { return new ItemStack(icon); }
    public Component displayName() { return Component.translatable("zone.townstead." + key); }
    public String letter() { return key.substring(0, 1).toUpperCase(java.util.Locale.ROOT); }

    @Override
    public String getSerializedName() { return key; }
}
