package net.mango.worldgen;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.FixedBiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.chunk.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

public class PlainsBorderChunkGenerator extends ChunkGenerator {
    public static final int RADIUS = 256;

    private static final int MIN_Y = -64;
    private static final int WORLD_HEIGHT = 384; // -64..319
    private static final int SEA_LEVEL = 63;

    // Our flat terrain levels
    private static final int BEDROCK_Y = -64;
    private static final int STONE_TOP = 59;
    private static final int DIRT_TOP = 62;
    private static final int GRASS_Y = 63;

    /**
     * "No-config" codec: the preset JSON only needs { "type": "plainsborder:plains_border" }.
     * We still must support serialization into level.dat, and we must get registry lookups
     * from RegistryOps to resolve minecraft:plains safely.
     */
    public static final MapCodec<PlainsBorderChunkGenerator> CODEC = new MapCodec<>() {
        @Override
        public <T> DataResult<PlainsBorderChunkGenerator> decode(DynamicOps<T> ops, MapLike<T> input) {
            if (!(ops instanceof RegistryOps<T> registryOps)) {
                return DataResult.error(() -> "PlainsBorderChunkGenerator requires RegistryOps");
            }

            var biomeLookup = registryOps.getEntryLookup(RegistryKeys.BIOME);
            RegistryEntry<Biome> plains = biomeLookup.orElseThrow().getOrThrow(BiomeKeys.PLAINS);

            return DataResult.success(new PlainsBorderChunkGenerator(plains));
        }

        @Override
        public <T> RecordBuilder<T> encode(PlainsBorderChunkGenerator value, DynamicOps<T> ops, RecordBuilder<T> builder) {
            // No fields to encode
            return builder;
        }

        @Override
        public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.empty();
        }
    };

    private PlainsBorderChunkGenerator(RegistryEntry<Biome> plains) {
        super(new FixedBiomeSource(plains));
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    private static boolean outsideBorder(ChunkPos pos) {
        int cx = Math.abs(pos.getStartX() + 8);
        int cz = Math.abs(pos.getStartZ() + 8);
        return Math.max(cx, cz) > RADIUS;
    }

    @Override
    public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig, StructureAccessor structureAccessor, Chunk chunk) {
        if (outsideBorder(chunk.getPos())) {
            return CompletableFuture.completedFuture(chunk);
        }

        return CompletableFuture.supplyAsync(() -> {
            BlockState bedrock = Blocks.BEDROCK.getDefaultState();
            BlockState stone = Blocks.STONE.getDefaultState();
            BlockState dirt = Blocks.DIRT.getDefaultState();
            BlockState grass = Blocks.GRASS_BLOCK.getDefaultState();

            BlockPos.Mutable mpos = new BlockPos.Mutable();
            ChunkPos cpos = chunk.getPos();

            int startX = cpos.getStartX();
            int startZ = cpos.getStartZ();

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    int wx = startX + lx;
                    int wz = startZ + lz;

                    mpos.set(wx, BEDROCK_Y, wz);
                    chunk.setBlockState(mpos, bedrock, 0);

                    for (int y = BEDROCK_Y + 1; y <= STONE_TOP; y++) {
                        mpos.set(wx, y, wz);
                        chunk.setBlockState(mpos, stone, 0);
                    }

                    for (int y = STONE_TOP + 1; y <= DIRT_TOP; y++) {
                        mpos.set(wx, y, wz);
                        chunk.setBlockState(mpos, dirt, 0);
                    }

                    mpos.set(wx, GRASS_Y, wz);
                    chunk.setBlockState(mpos, grass, 0);
                }
            }

            return chunk;
        });
    }


    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess, StructureAccessor structureAccessor, Chunk chunk) {
        if (outsideBorder(chunk.getPos())) return;
    }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structureAccessor, NoiseConfig noiseConfig, Chunk chunk) {
        if (outsideBorder(chunk.getPos())) return;
        // no-op; surface already placed in populateNoise
    }

    @Override
    public void generateFeatures(StructureWorldAccess world, Chunk chunk, StructureAccessor structureAccessor) {
        if (!outsideBorder(chunk.getPos())) return;
    }

    @Override
    public void populateEntities(ChunkRegion region) {
        if (outsideBorder(region.getCenterPos())) return;
        // no-op
    }

    @Override
    public int getWorldHeight() {
        return WORLD_HEIGHT;
    }

    @Override
    public int getSeaLevel() {
        return SEA_LEVEL;
    }

    @Override
    public int getMinimumY() {
        return 0;
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        // surface is GRASS_Y, so first-air-above is GRASS_Y + 1
        return GRASS_Y + 1;
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        BlockState[] states = new BlockState[WORLD_HEIGHT];

        BlockState bedrock = Blocks.BEDROCK.getDefaultState();
        BlockState stone = Blocks.STONE.getDefaultState();
        BlockState dirt = Blocks.DIRT.getDefaultState();
        BlockState grass = Blocks.GRASS_BLOCK.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();

        for (int i = 0; i < WORLD_HEIGHT; i++) {
            int y = MIN_Y + i;

            BlockState s;
            if (y == BEDROCK_Y) s = bedrock;
            else if (y <= STONE_TOP) s = stone;
            else if (y <= DIRT_TOP) s = dirt;
            else if (y == GRASS_Y) s = grass;
            else s = air;

            states[i] = s;
        }

        return new VerticalBlockSample(MIN_Y, states);
    }

    @Override
    public void appendDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        text.add("PlainsBorderChunkGenerator radius=" + RADIUS);
    }
}
