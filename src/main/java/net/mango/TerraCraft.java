package net.mango;

import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.mango.item.ModItems;
import net.mango.worldgen.PlainsBorderChunkGenerator;
import net.mango.worldgen.TerracraftFeatures;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TerraCraft implements ModInitializer {
	public static final String MOD_ID = "terracraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static final Identifier GENERATOR_ID = Identifier.of(MOD_ID, "plains_border");

	@Override
	public void onInitialize() {
		LOGGER.info("Hello Fabric world!");

		Registry.register(Registries.CHUNK_GENERATOR, GENERATOR_ID, PlainsBorderChunkGenerator.CODEC);

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            WorldBorder border = server.getOverworld().getWorldBorder();
			border.setCenter(0.0, 0.0);
			border.setSize(512.0);
		});

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayerEntity player = handler.getPlayer();
			String tag = "terracraft:first_join";
			if (!player.getCommandTags().contains(tag)) {
				player.addCommandTag(tag);

				player.getAttributeInstance(EntityAttributes.MAX_HEALTH).setBaseValue(10);
				player.getInventory().insertStack(0, Items.COPPER_SWORD.getDefaultStack());
				player.getInventory().insertStack(1, Items.COPPER_PICKAXE.getDefaultStack());
				player.getInventory().insertStack(2, Items.COPPER_AXE.getDefaultStack());
			}
		});

		UseItemCallback.EVENT.register(((player, world, hand) -> {
			var stack = player.getStackInHand(hand);

			if (world.isClient()) return ActionResult.PASS;

			if (stack.isOf(ModItems.LIFE_CRYSTAL)) {
                EntityAttributeInstance inst = player.getAttributeInstance(EntityAttributes.MAX_HEALTH);
				if (inst == null) return ActionResult.PASS;

				if (inst.getValue() > 38) {
					if (!world.isClient()) {
						world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_BLAZE_HURT, SoundCategory.PLAYERS, 1.0f, 1.0f);
					}

					return ActionResult.PASS;
				}

				inst.setBaseValue(player.getAttributeBaseValue(EntityAttributes.MAX_HEALTH) + 2);
				player.setHealth(player.getMaxHealth());

				if (!world.isClient()) {
					world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 1.0f, 1.0f);
				}

				if (!player.getAbilities().creativeMode) {
					stack.decrement(1);
				}

				 return ActionResult.SUCCESS;
			}

			return ActionResult.PASS;
		}));

		ModItems.initialize();
		TerracraftFeatures.register();
	}
}