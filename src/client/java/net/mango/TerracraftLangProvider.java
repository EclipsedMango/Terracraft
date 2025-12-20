package net.mango;

import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricLanguageProvider;
import net.mango.item.TerracraftItems;
import net.minecraft.registry.RegistryWrapper;

import java.util.concurrent.CompletableFuture;

public class TerracraftLangProvider extends FabricLanguageProvider {
    public TerracraftLangProvider(FabricDataOutput output, CompletableFuture<RegistryWrapper.WrapperLookup> registryLookup) {
        super(output, "en_us", registryLookup);
    }

    @Override
    public void generateTranslations(RegistryWrapper.WrapperLookup wrapperLookup, TranslationBuilder translationBuilder) {
        translationBuilder.add(TerracraftItems.LIFE_CRYSTAL, "Life Crystal");
        translationBuilder.add(TerracraftItems.CACTUS_CHESTPLATE, "Cactus Chestplate");
        translationBuilder.add("generator.terracraft.small_world", "Small World");
    }
}
