package net.mango.worldgen;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.mango.TerraCraft;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.GenerationStep;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;

public class TerracraftFeatures {
    public static final Identifier CAVE_CHEST_ID = Identifier.of(TerraCraft.MOD_ID, "cave_chest");
    public static Feature<DefaultFeatureConfig> CAVE_CHEST_FEATURE;

    public static void register() {
        CAVE_CHEST_FEATURE = Registry.register(Registries.FEATURE, CAVE_CHEST_ID, new CaveChestFeature(DefaultFeatureConfig.CODEC));
        BiomeModifications.addFeature(BiomeSelectors.foundInOverworld(), GenerationStep.Feature.UNDERGROUND_DECORATION, RegistryKey.of(RegistryKeys.PLACED_FEATURE, CAVE_CHEST_ID));
    }
}
