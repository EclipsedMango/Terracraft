package net.mango.item;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.mango.TerraCraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public class ModItems {
    public static final RegistryKey<Item> LIFE_CRYSTAL_KEY = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(TerraCraft.MOD_ID, "life_crystal"));

    public static final Item LIFE_CRYSTAL = Registry.register(Registries.ITEM, LIFE_CRYSTAL_KEY.getValue(), new Item(new Item.Settings().registryKey(LIFE_CRYSTAL_KEY)));

    public static void initialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS).register(entries -> entries.add(LIFE_CRYSTAL));
    }
}
