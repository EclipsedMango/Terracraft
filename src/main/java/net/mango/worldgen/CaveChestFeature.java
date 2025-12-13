package net.mango.worldgen;

import com.mojang.serialization.Codec;
import net.mango.TerraCraft;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.gen.feature.DefaultFeatureConfig;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.util.FeatureContext;

public class CaveChestFeature extends Feature<DefaultFeatureConfig> {
    public CaveChestFeature(Codec<DefaultFeatureConfig> configCodec) {
        super(configCodec);
    }

    @Override
    public boolean generate(FeatureContext<DefaultFeatureConfig> ctx) {
        StructureWorldAccess world = ctx.getWorld();

        var random = ctx.getRandom();
        if (random.nextInt(2) != 0) return false;

        BlockPos origin = ctx.getOrigin();
        BlockPos surfaceBlock = origin.down();

        for (int depth = 1; depth <= 10; depth++) {
            BlockPos pos = surfaceBlock.down(depth);

            if (!world.isAir(pos) || !world.isAir(pos.up())) continue;

            BlockPos floor = pos.down();
            if (!world.getBlockState(floor).isSideSolidFullSquare(world, floor, Direction.UP)) continue;

            if (world.getBlockState(pos.north()).isOf(Blocks.CHEST)) continue;
            if (world.getBlockState(pos.south()).isOf(Blocks.CHEST)) continue;
            if (world.getBlockState(pos.east()).isOf(Blocks.CHEST)) continue;
            if (world.getBlockState(pos.west()).isOf(Blocks.CHEST)) continue;

            var facing = Direction.Type.HORIZONTAL.random(random);
            world.setBlockState(pos, Blocks.CHEST.getDefaultState().with(ChestBlock.FACING, facing), 3);

            if (world.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                var tableKey = RegistryKey.of(RegistryKeys.LOOT_TABLE, Identifier.of(TerraCraft.MOD_ID, "chests/cave_chest"));
                chest.setLootTable(tableKey, random.nextLong());
                chest.markDirty();

                TerraCraft.LOGGER.info("cave_chest spawn success! Located at: {}", pos);
            }

            return true;
        }

        return false;
    }
}
