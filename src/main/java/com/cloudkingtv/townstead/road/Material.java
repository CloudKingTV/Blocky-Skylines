package com.cloudkingtv.townstead.road;

import com.cloudkingtv.townstead.Townstead;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A group of interchangeable building items ("any planks"). Each is an item tag under
 * {@code townstead:materials/}, so data packs can add modded blocks.
 */
public enum Material {
    DIRT("dirt", Items.DIRT),
    COBBLESTONE("cobblestone", Items.COBBLESTONE),
    STONE_BRICKS("stone_bricks", Items.STONE_BRICKS),
    PLANKS("planks", Items.OAK_PLANKS),
    LOGS("logs", Items.OAK_LOG),
    FENCES("fences", Items.OAK_FENCE),
    LANTERNS("lanterns", Items.LANTERN),
    LEAVES("leaves", Items.OAK_LEAVES),
    GLASS("glass", Items.GLASS_PANE),
    WOOL("wool", Items.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE)),
    WORKSTATIONS("workstations", Items.BARREL),
    HAY("hay", Items.HAY_BLOCK);

    /** Everything the Town Storehouse accepts. */
    public static final TagKey<Item> BUILDING_MATERIALS = ItemTags.create(Townstead.id("building_materials"));

    private final String key;
    private final Item icon;
    private final TagKey<Item> tag;

    Material(String key, Item icon) {
        this.key = key;
        this.icon = icon;
        this.tag = ItemTags.create(Townstead.id("materials/" + key));
    }

    public TagKey<Item> tag() { return tag; }
    public ItemStack icon() { return new ItemStack(icon); }
    public Component displayName() { return Component.translatable("material.townstead." + key); }

    public boolean matches(Item item) {
        return item.builtInRegistryHolder().is(tag);
    }
}
