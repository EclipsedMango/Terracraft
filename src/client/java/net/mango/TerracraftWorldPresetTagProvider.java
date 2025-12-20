package net.mango;

import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricTagProvider;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.WorldPreset;

import java.util.concurrent.CompletableFuture;

public class TerracraftWorldPresetTagProvider extends FabricTagProvider<WorldPreset> {
    public TerracraftWorldPresetTagProvider(FabricDataOutput output, CompletableFuture<RegistryWrapper.WrapperLookup> registries) {
        super(output, RegistryKeys.WORLD_PRESET, registries);
    }

    @Override
    protected void configure(RegistryWrapper.WrapperLookup lookup) {
        TagKey<WorldPreset> NORMAL = TagKey.of(RegistryKeys.WORLD_PRESET, Identifier.of("minecraft", "normal"));
        TagKey<WorldPreset> EXTENDED = TagKey.of(RegistryKeys.WORLD_PRESET, Identifier.of("minecraft", "extended"));

        getTagBuilder(NORMAL).addOptional(Identifier.of("terracraft", "small_world"));
        getTagBuilder(EXTENDED).addOptional(Identifier.of("terracraft", "small_world"));
    }
}
