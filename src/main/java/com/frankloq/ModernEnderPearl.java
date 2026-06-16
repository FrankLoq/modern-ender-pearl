package com.frankloq;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ModernEnderPearl implements ModInitializer {
	public static final String MOD_ID = "modern-ender-pearl";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModConfig.load();
		LOGGER.info("ModernEnderPearl initialized.");
	}

}