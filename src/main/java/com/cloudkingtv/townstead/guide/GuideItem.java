package com.cloudkingtv.townstead.guide;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** The Townstead Guide: use it to read about founding and running a town. */
public final class GuideItem extends Item {
    /** Opens the guide's screen; set by the client (a dedicated server never opens it). */
    public static Runnable open = () -> {};

    public GuideItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) open.run();
        return InteractionResult.SUCCESS;
    }
}
