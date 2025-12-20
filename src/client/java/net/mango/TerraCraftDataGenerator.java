package net.mango;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

public class TerraCraftDataGenerator implements DataGeneratorEntrypoint {
	@Override
	public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
		FabricDataGenerator.Pack pack = fabricDataGenerator.createPack();

		pack.addProvider(TerracraftLangProvider::new);
		pack.addProvider(TerracraftWorldPresetTagProvider::new);
		pack.addProvider(TerracraftModelProvider::new);
		pack.addProvider(TerracraftItemTagProvider::new);
	}
}
