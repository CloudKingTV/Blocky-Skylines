package com.cloudkingtv.townstead.registry;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.city.TownHallBlock;
import com.cloudkingtv.townstead.planner.PlannerReturn;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class TownsteadRegistry {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Townstead.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Townstead.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Townstead.MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Townstead.MODID);

    public static final DeferredBlock<TownHallBlock> TOWN_HALL = BLOCKS.registerBlock("town_hall", TownHallBlock::new,
            p -> p.mapColor(MapColor.WOOD).strength(3.0f, 1200.0f).sound(SoundType.WOOD));
    public static final DeferredItem<BlockItem> TOWN_HALL_ITEM = ITEMS.registerSimpleBlockItem("town_hall", TOWN_HALL,
            p -> p.rarity(Rarity.UNCOMMON).stacksTo(1));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.townstead"))
            .icon(() -> TOWN_HALL_ITEM.get().getDefaultInstance())
            .displayItems((params, out) -> out.accept(TOWN_HALL_ITEM.get()))
            .build());

    /** Where to put a player back when they leave the planner (persisted in case they log out mid-plan). */
    public static final Supplier<AttachmentType<PlannerReturn>> PLANNER_RETURN = ATTACHMENTS.register("planner_return",
            () -> AttachmentType.builder(() -> PlannerReturn.NONE).serialize(PlannerReturn.MAP_CODEC).build());

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
        ATTACHMENTS.register(modBus);
    }

    private TownsteadRegistry() {}
}
