package com.frankloq;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(ModernEnderPearl.MOD_ID)
public class ModernEnderPearl {
    public static final String MOD_ID = "modernenderpearl";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public ModernEnderPearl(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, ModConfig.CONFIG);
        LOGGER.info("ModernEnderPearl initialized.");
    }
}