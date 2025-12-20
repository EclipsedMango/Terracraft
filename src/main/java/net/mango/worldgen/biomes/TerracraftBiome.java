package net.mango.worldgen.biomes;

import net.mango.worldgen.noise.SimplexTerracraftNoise;
import net.mango.worldgen.noise.TerracraftNoise;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.random.Random;

public enum TerracraftBiome {
    PLAINS,
    DESERT,
    SNOW,
    JUNGLE,
    OCEAN,
    BEACH;

    public TerracraftNoise terrainHeightMap(Random random) {
        return switch (this) {
            case PLAINS, JUNGLE, OCEAN -> (x, z) -> 1;
            case BEACH, DESERT, SNOW -> (x, y) -> 8;
        };
    }

    public Block terrainBlock() {
        return switch (this) {
            case PLAINS, SNOW, JUNGLE -> Blocks.DIRT;
            case DESERT -> Blocks.RED_SANDSTONE;
            case BEACH -> Blocks.SANDSTONE;
            case OCEAN -> Blocks.SAND;
        };
    }

    public TerracraftNoise surfaceHeightMap(Random random) {
        return switch (this) {
            case PLAINS -> new SimplexTerracraftNoise(random).scale(16.0).offset(64.0).octavate(4, 2.0, 0.5).frequency(1.0 / 128.0);
            case SNOW -> new SimplexTerracraftNoise(random).scale(20.0).offset(64.0).octavate(4, 2.0, 0.5).frequency(1.0 / 128.0);
            case DESERT -> new SimplexTerracraftNoise(random).scale(2.0).offset(64.0).octavate(4, 2.0, 0.5).frequency(1.0 / 128.0);
            case JUNGLE -> new SimplexTerracraftNoise(random).scale(24.0).offset(64.0).octavate(4, 2.0, 0.5).frequency(1.0 / 128.0);
            case BEACH -> new SimplexTerracraftNoise(random).scale(1.0).offset(64.0).octavate(4, 2.0, 0.5).frequency(1.0 / 128.0);
            case OCEAN -> new SimplexTerracraftNoise(random).scale(1.0).offset(32.0).octavate(4, 2.0, 0.5).frequency(1.0 / 128.0);
        };
    }

    public Block surfaceBlock() {
        return switch (this) {
            case PLAINS -> Blocks.GRASS_BLOCK;
            case DESERT -> Blocks.RED_SAND;
            case SNOW -> Blocks.SNOW_BLOCK;
            case JUNGLE -> Blocks.PODZOL;
            case BEACH, OCEAN -> Blocks.SAND;
        };
    }
}
