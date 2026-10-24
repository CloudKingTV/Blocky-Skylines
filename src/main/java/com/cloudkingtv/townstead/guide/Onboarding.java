package com.cloudkingtv.townstead.guide;

import com.cloudkingtv.townstead.TownsteadConfig;
import com.cloudkingtv.townstead.registry.TownsteadRegistry;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** What a player has been given to get started (kept with the player, so it happens once per world). */
public record Onboarding(boolean guideGiven) {
    public static final Onboarding NONE = new Onboarding(false);
    public static final MapCodec<Onboarding> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.BOOL.optionalFieldOf("guide_given", false).forGetter(Onboarding::guideGiven)
    ).apply(i, Onboarding::new));

    /** On a player's first visit: a welcome, and the guide if the server hands it out. */
    public static void welcome(ServerPlayer player) {
        if (player.getData(TownsteadRegistry.ONBOARDING).guideGiven()) return;
        player.setData(TownsteadRegistry.ONBOARDING, new Onboarding(true));
        player.sendSystemMessage(Component.translatable("message.townstead.welcome").withStyle(ChatFormatting.GOLD));
        if (!TownsteadConfig.GIVE_GUIDE.get()) return;
        ItemStack guide = new ItemStack(TownsteadRegistry.GUIDE.get());
        player.getInventory().placeItemBackInInventory(guide, net.minecraft.util.Prediction.SERVER_ONLY);
        player.sendSystemMessage(Component.translatable("message.townstead.welcome.guide").withStyle(ChatFormatting.GRAY));
    }
}
