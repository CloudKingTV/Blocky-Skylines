package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.life.CitizenNpc;
import java.util.List;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.npc.BabyVillagerModel;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.client.renderer.entity.AgeableMobRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.layers.CustomHeadLayer;
import net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;

/**
 * Citizens look like the villagers they're modelled on: the vanilla villager model and textures, in the biome
 * clothes of their town's style and the outfit of their trade (see {@code Citizens.OUTFITS}).
 */
public final class CitizenRenderer extends AgeableMobRenderer<CitizenNpc, VillagerRenderState, VillagerModel> {
    private static final Identifier BASE = Identifier.withDefaultNamespace("textures/entity/villager/villager.png");
    private static final Identifier BABY = Identifier.withDefaultNamespace("textures/entity/villager/villager_baby.png");
    /** Same order as Citizens.OUTFITS and Citizens.TYPES (the look's low and high bits). */
    private static final List<String> OUTFITS = List.of("none", "farmer", "librarian", "cleric", "cartographer", "fletcher", "mason",
            "toolsmith", "weaponsmith", "armorer", "butcher", "leatherworker", "shepherd", "fisherman", "nitwit");
    private static final List<String> TYPES = List.of("plains", "desert", "savanna", "taiga", "snow", "jungle", "swamp");

    public CitizenRenderer(EntityRendererProvider.Context context) {
        super(context, new VillagerModel(context.bakeLayer(ModelLayers.VILLAGER)), new BabyVillagerModel(context.bakeLayer(ModelLayers.VILLAGER_BABY)), 0.5f);
        addLayer(new CustomHeadLayer<>(this, context.getModelSet(), context.getPlayerSkinRenderCache(),
                net.minecraft.client.renderer.entity.VillagerRenderer.CUSTOM_HEAD_TRANSFORMS));
        addLayer(new VillagerProfessionLayer<>(this, context.getResourceManager(), "villager",
                new VillagerModel(context.bakeLayer(ModelLayers.VILLAGER_NO_HAT)), new BabyVillagerModel(context.bakeLayer(ModelLayers.VILLAGER_BABY_NO_HAT))));
    }

    @Override
    public Identifier getTextureLocation(VillagerRenderState state) {
        return state.isBaby ? BABY : BASE;
    }

    @Override
    public VillagerRenderState createRenderState() {
        return new VillagerRenderState();
    }

    @Override
    public void extractRenderState(CitizenNpc entity, VillagerRenderState state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        int look = entity.look();
        String type = TYPES.get(Math.floorMod(look >> 5, TYPES.size())), outfit = OUTFITS.get(Math.floorMod(look & 31, OUTFITS.size()));
        var reg = entity.level().registryAccess();
        state.villagerData = new VillagerData(
                reg.getOrThrow(ResourceKey.create(Registries.VILLAGER_TYPE, Identifier.withDefaultNamespace(type))),
                reg.getOrThrow(ResourceKey.create(Registries.VILLAGER_PROFESSION, Identifier.withDefaultNamespace(outfit))), 2);
    }
}
