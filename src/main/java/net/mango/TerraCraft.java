package net.mango;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.loader.api.FabricLoader;
import net.mango.worldgen.TerracraftFeatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TerraCraft implements ModInitializer {
	public static final String MOD_ID = "terracraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Hello Fabric world!");

		FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(mod -> {
			var p = mod.findPath("data/terracraft/loot_table/chests/cave_chest.json");
			TerraCraft.LOGGER.info("Loot table file present in mod container? {}", p.isPresent());
			p.ifPresent(path -> TerraCraft.LOGGER.info("Loot table path: {}", path));
		});

		TerracraftFeatures.register();
	}
}