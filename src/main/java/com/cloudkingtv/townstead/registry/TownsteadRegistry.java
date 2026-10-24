package com.cloudkingtv.townstead.registry;

import com.cloudkingtv.townstead.Townstead;
import com.cloudkingtv.townstead.city.TownHallBlock;
import com.cloudkingtv.townstead.city.TownStorehouseBlock;
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
    public static final DeferredRegister.Entities ENTITIES = DeferredRegister.createEntities(Townstead.MODID);
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Townstead.MODID);

    public static final DeferredBlock<TownHallBlock> TOWN_HALL = BLOCKS.registerBlock("town_hall", TownHallBlock::new,
            p -> p.mapColor(MapColor.WOOD).strength(3.0f, 1200.0f).sound(SoundType.WOOD));
    public static final DeferredItem<BlockItem> TOWN_HALL_ITEM = ITEMS.registerSimpleBlockItem("town_hall", TOWN_HALL,
            p -> p.rarity(Rarity.UNCOMMON).stacksTo(1));

    public static final DeferredBlock<TownStorehouseBlock> TOWN_STOREHOUSE = BLOCKS.registerBlock("town_storehouse", TownStorehouseBlock::new,
            p -> p.mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD));
    public static final DeferredItem<BlockItem> TOWN_STOREHOUSE_ITEM = ITEMS.registerSimpleBlockItem("town_storehouse", TOWN_STOREHOUSE);

    public static final DeferredItem<com.cloudkingtv.townstead.guide.GuideItem> GUIDE = ITEMS.registerItem("guide",
            com.cloudkingtv.townstead.guide.GuideItem::new, p -> p.stacksTo(1));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.townstead"))
            .icon(() -> TOWN_HALL_ITEM.get().getDefaultInstance())
            .displayItems((params, out) -> {
                out.accept(TOWN_HALL_ITEM.get());
                out.accept(TOWN_STOREHOUSE_ITEM.get());
                out.accept(GUIDE.get());
            })
            .build());

    /** A resident out on the streets (never saved; see {@link com.cloudkingtv.townstead.life.Citizens}). */
    public static final Supplier<net.minecraft.world.entity.EntityType<com.cloudkingtv.townstead.life.CitizenNpc>> CITIZEN = ENTITIES.registerEntityType(
            "citizen", com.cloudkingtv.townstead.life.CitizenNpc::new, net.minecraft.world.entity.MobCategory.MISC,
            b -> b.sized(0.6f, 1.95f).clientTrackingRange(8).noSave());

    /** Where to put a player back when they leave the planner (persisted in case they log out mid-plan). */
    public static final Supplier<AttachmentType<PlannerReturn>> PLANNER_RETURN = ATTACHMENTS.register("planner_return",
            () -> AttachmentType.builder(() -> PlannerReturn.NONE).serialize(PlannerReturn.MAP_CODEC).build());

    /** What a player's been given to get started: the guide, once. */
    public static final Supplier<AttachmentType<com.cloudkingtv.townstead.guide.Onboarding>> ONBOARDING = ATTACHMENTS.register("onboarding",
            () -> AttachmentType.builder(() -> com.cloudkingtv.townstead.guide.Onboarding.NONE)
                    .serialize(com.cloudkingtv.townstead.guide.Onboarding.MAP_CODEC).copyOnDeath().build());

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        TABS.register(modBus);
        ATTACHMENTS.register(modBus);
        ENTITIES.register(modBus);
        modBus.addListener((net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent e) ->
                e.put(CITIZEN.get(), com.cloudkingtv.townstead.life.CitizenNpc.createAttributes().build()));
    }

    private TownsteadRegistry() {}
}
