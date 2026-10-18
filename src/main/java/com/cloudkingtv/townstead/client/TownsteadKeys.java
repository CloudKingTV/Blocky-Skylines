package com.cloudkingtv.townstead.client;

import com.cloudkingtv.townstead.Townstead;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

public final class TownsteadKeys {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Townstead.id("main"));
    public static final KeyMapping PLANNER = new KeyMapping("key.townstead.planner", InputConstants.KEY_M, CATEGORY);

    private TownsteadKeys() {}
}
