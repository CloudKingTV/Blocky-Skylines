package com.cloudkingtv.townstead;

import com.cloudkingtv.townstead.registry.TownsteadRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(Townstead.MODID)
public final class Townstead {
    public static final String MODID = "townstead";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Townstead(IEventBus modBus, ModContainer container) {
        TownsteadRegistry.register(modBus);
        container.registerConfig(ModConfig.Type.CLIENT, TownsteadConfig.CLIENT_SPEC);
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MODID, path);
    }
}
