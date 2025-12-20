package net.mango.item;

import com.google.common.collect.Maps;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.mango.TerraCraft;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.equipment.ArmorMaterial;
import net.minecraft.item.equipment.EquipmentAsset;
import net.minecraft.item.equipment.EquipmentType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

import java.util.Map;

public class TerracraftItems {

    static RegistryKey<EquipmentAsset> CACTUS_EQUIPMENT_ASSET = RegistryKey.of(RegistryKey.ofRegistry(Identifier.ofVanilla("equipment_asset")), Identifier.of(TerraCraft.MOD_ID, "cactus"));

    static ArmorMaterial CACTUS = new ArmorMaterial(5, createDefenseMap(1, 2, 3, 1, 3), 15, SoundEvents.ITEM_ARMOR_EQUIP_LEATHER, 0.0F, 0.0F, ItemTags.REPAIRS_LEATHER_ARMOR, CACTUS_EQUIPMENT_ASSET);

    public static final RegistryKey<Item> CACTUS_ARMOR_KEY = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(TerraCraft.MOD_ID, "cactus_chestplate"));
    public static final Item CACTUS_CHESTPLATE = Registry.register(Registries.ITEM, CACTUS_ARMOR_KEY.getValue(), new Item(new Item.Settings().armor(CACTUS, EquipmentType.CHESTPLATE).registryKey(CACTUS_ARMOR_KEY)));

    public static final RegistryKey<Item> LIFE_CRYSTAL_KEY = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(TerraCraft.MOD_ID, "life_crystal"));
    public static final Item LIFE_CRYSTAL = Registry.register(Registries.ITEM, LIFE_CRYSTAL_KEY.getValue(), new Item(new Item.Settings().registryKey(LIFE_CRYSTAL_KEY)));

    public static void initialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.INGREDIENTS).register(entries -> entries.add(LIFE_CRYSTAL));
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.COMBAT).register(entries -> entries.add(CACTUS_CHESTPLATE));
    }

    private static Map<EquipmentType, Integer> createDefenseMap(int bootsDefense, int leggingsDefense, int chestplateDefense, int helmetDefense, int bodyDefense) {
        return Maps.newEnumMap(Map.of(EquipmentType.BOOTS, bootsDefense, EquipmentType.LEGGINGS, leggingsDefense, EquipmentType.CHESTPLATE, chestplateDefense, EquipmentType.HELMET, helmetDefense, EquipmentType.BODY, bodyDefense));
    }
}
