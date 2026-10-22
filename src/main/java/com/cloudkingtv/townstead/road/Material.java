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
    DIRT("dirt", Items.DIRT, 1),
    COBBLESTONE("cobblestone", Items.COBBLESTONE, 2),
    STONE_BRICKS("stone_bricks", Items.STONE_BRICKS, 4),
    PLANKS("planks", Items.OAK_PLANKS, 2),
    LOGS("logs", Items.OAK_LOG, 4),
    FENCES("fences", Items.OAK_FENCE, 3),
    LANTERNS("lanterns", Items.LANTERN, 12),
    LEAVES("leaves", Items.OAK_LEAVES, 1),
    GLASS("glass", Items.GLASS_PANE, 4),
    WOOL("wool", Items.WOOL.pick(net.minecraft.world.item.DyeColor.WHITE), 5),
    WORKSTATIONS("workstations", Items.BARREL, 20),
    HAY("hay", Items.HAY_BLOCK, 4),
    SANDSTONE("sandstone", Items.SANDSTONE, 3),
    TERRACOTTA("terracotta", Items.TERRACOTTA, 5),
    BRICKS("bricks", Items.BRICKS, 5);

    /** Everything the Town Storehouse accepts. */
    public static final TagKey<Item> BUILDING_MATERIALS = ItemTags.create(Townstead.id("building_materials"));

    private final String key;
    private final Item icon;
    private final TagKey<Item> tag;
    /** What one unit fetches when the town sells a surplus, in hundredths of an emerald. */
    public final int price;

    /** Buying in what the town can't make costs this many times the selling price. */
    public static final int IMPORT_MARKUP = 3;

    Material(String key, Item icon, int price) {
        this.key = key;
        this.icon = icon;
        this.tag = ItemTags.create(Townstead.id("materials/" + key));
        this.price = price;
    }

    /** The item the town's own workers make of this material. */
    public Item item() { return icon; }
    public int importPrice() { return price * IMPORT_MARKUP; }

    public TagKey<Item> tag() { return tag; }
    public ItemStack icon() { return new ItemStack(icon); }
    public Component displayName() { return Component.translatable("material.townstead." + key); }

    public boolean matches(Item item) {
        return item.builtInRegistryHolder().is(tag);
    }
}
