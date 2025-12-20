package net.mango;

import net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.mango.item.TerracraftItems;
import net.minecraft.client.data.BlockStateModelGenerator;
import net.minecraft.client.data.ItemModelGenerator;
import net.minecraft.client.data.Models;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;

public class TerracraftModelProvider extends FabricModelProvider {
    public TerracraftModelProvider(FabricDataOutput output) {
        super(output);
    }

    @Override
    public void generateItemModels(ItemModelGenerator itemModelGenerator) {
        itemModelGenerator.register(TerracraftItems.LIFE_CRYSTAL, Models.GENERATED);

        itemModelGenerator.registerArmor(TerracraftItems.CACTUS_CHESTPLATE, RegistryKey.of(RegistryKey.ofRegistry(Identifier.ofVanilla("equipment_asset")), Identifier.of(TerraCraft.MOD_ID, "cactus")), ItemModelGenerator.CHESTPLATE_TRIM_ID_PREFIX, false);
    }

    @Override
    public void generateBlockStateModels(BlockStateModelGenerator blockStateModelGenerator) {

    }

}
