package net.mango.worldgen;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import net.mango.TerraCraft;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.registry.*;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.math.random.RandomSplitter;
import net.minecraft.world.*;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.source.FixedBiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.feature.PlacedFeature;
import net.minecraft.world.gen.feature.TreePlacedFeatures;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.chunk.Blender;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static net.mango.worldgen.SmallWorldParams.smoothstep;

public class SmallWorldChunkGenerator extends ChunkGenerator {
    public static final int RADIUS = SmallWorldParams.RADIUS;

    private static final int MIN_Y = -64;
    private static final int WORLD_HEIGHT = 384;
    private static final int SEA_LEVEL = SmallWorldParams.SEA_LEVEL;

    private static final int BEDROCK_Y = -64;

    private static final int BASE_SURFACE_Y = 63;
    private static final double HILL_SCALE_1 = SmallWorldParams.HILL_SCALE_1;
    private static final double HILL_SCALE_2 = SmallWorldParams.HILL_SCALE_2;
    private static final double HILL_AMP_1 = SmallWorldParams.HILL_AMP_1;
    private static final double HILL_AMP_2 = SmallWorldParams.HILL_AMP_1;

    private static final int BEACH_WIDTH = SmallWorldParams.BEACH_WIDTH;
    private static final int SLOPE_WIDTH = SmallWorldParams.SLOPE_WIDTH;
    private static final int OCEAN_FLOOR_Y = SmallWorldParams.OCEAN_FLOOR_Y;
    private static final int DEEP_OCEAN_FLOOR_Y = SEA_LEVEL - 16;

    private static final Identifier HEIGHT_DERIVER_ID = Identifier.of(TerraCraft.MOD_ID, "height_deriver");
    private static final Identifier HEIGHT_RANDOM_ID  = Identifier.of(TerraCraft.MOD_ID, "height_random");
    private static final Identifier PLANT_DERIVER_ID = Identifier.of(TerraCraft.MOD_ID, "plants_deriver");

    private final RegistryEntry<Biome> plains;
    private final RegistryEntry<Biome> beach;
    private final RegistryEntry<Biome> ocean;

    private volatile SimplexNoiseSampler heightNoise;

    public static final MapCodec<SmallWorldChunkGenerator> CODEC = new MapCodec<>() {
        @Override
        public <T> DataResult<SmallWorldChunkGenerator> decode(DynamicOps<T> ops, MapLike<T> input) {
            if (!(ops instanceof RegistryOps<T> registryOps)) {
                return DataResult.error(() -> "PlainsBorderChunkGenerator requires RegistryOps");
            }

            Optional<RegistryEntryLookup<Biome>> biomeLookup = registryOps.getEntryLookup(RegistryKeys.BIOME);
            RegistryEntry<Biome> plains = biomeLookup.orElseThrow().getOrThrow(BiomeKeys.PLAINS);
            RegistryEntry<Biome> beach = biomeLookup.orElseThrow().getOrThrow(BiomeKeys.BEACH);
            RegistryEntry<Biome> ocean = biomeLookup.orElseThrow().getOrThrow(BiomeKeys.OCEAN);

            return DataResult.success(new SmallWorldChunkGenerator(plains, beach, ocean));
        }

        @Override
        public <T> RecordBuilder<T> encode(SmallWorldChunkGenerator value, DynamicOps<T> ops, RecordBuilder<T> builder) {
            return builder;
        }

        @Override
        public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.empty();
        }
    };

    private SmallWorldChunkGenerator(RegistryEntry<Biome> plains, RegistryEntry<Biome> beach, RegistryEntry<Biome> ocean) {
        super(new FixedBiomeSource(plains));
        this.plains = plains;
        this.beach = beach;
        this.ocean = ocean;
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

    private void ensureNoise(NoiseConfig noiseConfig) {
        if (heightNoise != null) return;

        synchronized (this) {
            if (heightNoise != null) return;

            Random rand = noiseConfig.getOrCreateRandomDeriver(HEIGHT_DERIVER_ID).split(HEIGHT_RANDOM_ID);
            heightNoise = new SimplexNoiseSampler(rand);
        }
    }

    private int surfaceY(int x, int z, NoiseConfig noiseConfig) {
        ensureNoise(noiseConfig);

        double n1 = heightNoise.sample(x * HILL_SCALE_1, z * HILL_SCALE_1);
        double n2 = heightNoise.sample(x * HILL_SCALE_2, z * HILL_SCALE_2);

        double inland = BASE_SURFACE_Y + (n1 * HILL_AMP_1) + (n2 * HILL_AMP_2);

        double oceanNoise = heightNoise.sample(x * 0.02, z * 0.02) * 2.0;
        double ocean = (OCEAN_FLOOR_Y + oceanNoise);

        double b = oceanBlend(x, z);
        double deep = DEEP_OCEAN_FLOOR_Y + oceanNoise;
        double oceanTarget = MathHelper.lerp(b, ocean, deep);

        double h = MathHelper.lerp(b, inland, oceanTarget);

        int min = MIN_Y + 1;
        int max = MIN_Y + WORLD_HEIGHT - 2;
        return MathHelper.clamp((int)Math.round(h), min, max);
    }

    private Random plantRandom(NoiseConfig noiseConfig, int x, int z) {
        RandomSplitter deriver = noiseConfig.getOrCreateRandomDeriver(PLANT_DERIVER_ID);
        long salt = ((long) x * 341873128712L) ^ ((long) z * 132897987541L);
        return deriver.split(salt);
    }

    private static double oceanBlend(int x, int z) {
        int d = Math.max(Math.abs(x), Math.abs(z));
        int start = RADIUS - BEACH_WIDTH;
        int end = RADIUS + SLOPE_WIDTH;
        return smoothstep((d - start) / (double)(end - start));
    }

    @Override
    public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig, StructureAccessor structureAccessor, Chunk chunk) {
        generateTerrain(noiseConfig, chunk);
        return CompletableFuture.completedFuture(chunk);
    }

    private void generateTerrain(NoiseConfig noiseConfig, Chunk chunk) {
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

                int topY = surfaceY(wx, wz, noiseConfig);

                mpos.set(wx, BEDROCK_Y, wz);
                chunk.setBlockState(mpos, bedrock, 0);

                int stoneTop = Math.max(BEDROCK_Y + 1, topY - 4);

                for (int y = BEDROCK_Y + 1; y <= stoneTop; y++) {
                    mpos.set(wx, y, wz);
                    chunk.setBlockState(mpos, stone, 0);
                }

                for (int y = stoneTop + 1; y <= topY - 1; y++) {
                    mpos.set(wx, y, wz);
                    chunk.setBlockState(mpos, dirt, 0);
                }

                mpos.set(wx, topY, wz);
                chunk.setBlockState(mpos, grass, 0);

                double oceanBlend = oceanBlend(wx, wz);

                // second condition is for blocks to be different at deep sea levels
                boolean isGrassBlock = !(topY <= SEA_LEVEL + 1 && oceanBlend > 0.15) && !(oceanBlend > 0.65);
                BlockState topBlock = (isGrassBlock) ? Blocks.GRASS_BLOCK.getDefaultState() : Blocks.SAND.getDefaultState();

                mpos.set(wx, topY, wz);
                chunk.setBlockState(mpos, topBlock, 0);

                if (topY < SEA_LEVEL) {
                    BlockState water = Blocks.WATER.getDefaultState();
                    for (int y = topY + 1; y <= SEA_LEVEL; y++) {
                        mpos.set(wx, y, wz);
                        if (chunk.getBlockState(mpos).isAir()) {
                            chunk.setBlockState(mpos, water, 0);
                        }
                    }
                }

                if (topY <= SEA_LEVEL || oceanBlend > 0.9) continue;

                int plantY = topY + 1;
                if (!chunk.getBlockState(mpos).isOf(Blocks.GRASS_BLOCK)) continue;

                Random pr = plantRandom(noiseConfig, wx, wz);

                // density (example: 75% of blocks get a plant) without clumping
                if (pr.nextInt(100) >= 75) continue;

                // type (example: 80% grass, 20% non-grass)
                boolean isGrass = pr.nextInt(100) < 80;
                boolean tall = pr.nextInt(100) < 1;
                boolean bush = pr.nextInt(100) < 60;

                mpos.set(wx, plantY, wz);

                if (!chunk.getBlockState(mpos).isAir()) continue;

                if (tall) {
                    mpos.set(wx, plantY + 1, wz);
                    if (!chunk.getBlockState(mpos).isAir()) continue;

                    BlockState lower = Blocks.TALL_GRASS.getDefaultState().with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
                    BlockState upper = Blocks.TALL_GRASS.getDefaultState().with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);

                    mpos.set(wx, plantY, wz);
                    chunk.setBlockState(mpos, lower, 0);

                    mpos.set(wx, plantY + 1, wz);
                    chunk.setBlockState(mpos, upper, 0);

                    continue;
                }

                BlockState blockState = (isGrass) ? Blocks.SHORT_GRASS.getDefaultState() : (bush) ? Blocks.BUSH.getDefaultState() : Blocks.FERN.getDefaultState();
                chunk.setBlockState(mpos, blockState, 0);
            }
        }
    }

    @Override
    public CompletableFuture<Chunk> populateBiomes(NoiseConfig noiseConfig, Blender blender, StructureAccessor structureAccessor, Chunk chunk) {
        ChunkPos cp = chunk.getPos();
        var sampler = noiseConfig.getMultiNoiseSampler();

        int startQuartX = cp.x << 2;
        int startQuartZ = cp.z << 2;

        for (int sectionY = chunk.getBottomSectionCoord(); sectionY < chunk.getTopSectionCoord(); sectionY++) {
            int startQuartY = sectionY << 2;
            ChunkSection section = chunk.getSection(chunk.sectionCoordToIndex(sectionY));

            section.populateBiomes((qx, qy, qz, s) -> {
                int wx = (qx << 2) + 2;
                int wz = (qz << 2) + 2;

                int topY = surfaceY(wx, wz, noiseConfig);
                double b = oceanBlend(wx, wz);

                if (topY < SEA_LEVEL) return this.ocean;
                if (b > 0.10 && topY <= SEA_LEVEL + 2) return this.beach;
                return this.plains;

            }, sampler, startQuartX, startQuartY, startQuartZ);
        }

        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess, StructureAccessor structureAccessor, Chunk chunk) {
        if (outsideBorder(chunk.getPos())) return;
    }

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structureAccessor, NoiseConfig noiseConfig, Chunk chunk) {
        if (outsideBorder(chunk.getPos())) return;
    }

    @Override
    public void generateFeatures(StructureWorldAccess world, Chunk chunk, StructureAccessor structureAccessor) {
        if (outsideBorder(chunk.getPos())) return;

        ChunkPos cp = chunk.getPos();

        double centerBlend = oceanBlend(cp.getCenterX(), cp.getCenterZ());
        if (centerBlend > 0.9) return;

        Registry<PlacedFeature> placed = world.getRegistryManager().getOrThrow(RegistryKeys.PLACED_FEATURE);
        PlacedFeature oakChecked = placed.get(TreePlacedFeatures.OAK_CHECKED);
        if (oakChecked == null) return;

        long s = world.getSeed() ^ (cp.x * 341873128712L) ^ (cp.z * 132897987541L) ^ 0x6A09E667F3BCC909L;
        Random rand = Random.create(s);

        int attempts = 1 + rand.nextInt(2);
        for (int i = 0; i < attempts; i++) {
            int x = cp.getStartX() + rand.nextInt(16);
            int z = cp.getStartZ() + rand.nextInt(16);

            int y = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z);
            BlockPos origin = new BlockPos(x, y, z);

            oakChecked.generate(world, this, rand, origin);
        }
    }

    @Override
    public void populateEntities(ChunkRegion region) {
        if (outsideBorder(region.getCenterPos())) return;
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
        return MIN_Y;
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        return surfaceY(x, z, noiseConfig) + 1;
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        BlockState[] states = new BlockState[WORLD_HEIGHT];

        BlockState bedrock = Blocks.BEDROCK.getDefaultState();
        BlockState stone = Blocks.STONE.getDefaultState();
        BlockState dirt = Blocks.DIRT.getDefaultState();
        BlockState grass = Blocks.GRASS_BLOCK.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();

        int topY = surfaceY(x, z, noiseConfig);
        int stoneTop = Math.max(BEDROCK_Y + 1, topY - 4);

        for (int i = 0; i < WORLD_HEIGHT; i++) {
            int y = MIN_Y + i;

            BlockState s;
            if (y == BEDROCK_Y) s = bedrock;
            else if (y <= stoneTop) s = stone;
            else if (y < topY) s = dirt;
            else if (y == topY) s = grass;
            else if (y <= SEA_LEVEL) s = Blocks.WATER.getDefaultState();
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
