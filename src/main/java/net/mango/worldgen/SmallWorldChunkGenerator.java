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
import net.minecraft.util.math.Vec3i;
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

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static net.mango.worldgen.SmallWorldParams.smoothstep;

public class SmallWorldChunkGenerator extends ChunkGenerator {
    public static final int RADIUS = SmallWorldParams.RADIUS;

    private static final int MIN_Y = -64;
    private static final int WORLD_HEIGHT = 384;
    private static final int SEA_LEVEL = SmallWorldParams.SEA_LEVEL;

    private static final int BEDROCK_Y = -64;

    private static final int BASE_SURFACE_Y = SmallWorldParams.BASE_SURFACE_Y;

    private static final int BEACH_WIDTH = SmallWorldParams.BEACH_WIDTH;
    private static final int SLOPE_WIDTH = SmallWorldParams.SLOPE_WIDTH;
    private static final int OCEAN_FLOOR_Y = SmallWorldParams.OCEAN_FLOOR_Y;
    private static final int DEEP_OCEAN_FLOOR_Y = SEA_LEVEL - 16;

    private static final Identifier HEIGHT_DERIVER_ID = Identifier.of(TerraCraft.MOD_ID, "height_deriver");
    private static final Identifier HEIGHT_RANDOM_ID  = Identifier.of(TerraCraft.MOD_ID, "height_random");

    private static final Identifier PLANT_DERIVER_ID = Identifier.of(TerraCraft.MOD_ID, "plants_deriver");

    private static final Identifier TEMP_DERIVER_ID = Identifier.of(TerraCraft.MOD_ID, "temp_deriver");
    private static final Identifier TEMP_RANDOM_ID = Identifier.of(TerraCraft.MOD_ID, "temp_random");

    private final Map<CustomBiomeEnum, RegistryEntry<Biome>> biomes;

    private boolean initFinished = false;

    private volatile SimplexNoiseSampler heightNoise;
    private SimplexNoiseSampler tempMap;

    private static final List<CustomBiomeBlendParams> BIOME_BLEND_PARAMS = List.of(
            new CustomBiomeBlendParams(CustomBiomeEnum.PLAINS, 0.0, 0.0),
            new CustomBiomeBlendParams(CustomBiomeEnum.DESERT, 1.0, 0.6),
            new CustomBiomeBlendParams(CustomBiomeEnum.SNOW, -1.0, 0.6),
            new CustomBiomeBlendParams(CustomBiomeEnum.BEACH, 0.2, 0.95),
            new CustomBiomeBlendParams(CustomBiomeEnum.OCEAN, 0.0, 1.0)
    );

    public static final MapCodec<SmallWorldChunkGenerator> CODEC = new MapCodec<>() {
        @Override
        public <T> DataResult<SmallWorldChunkGenerator> decode(DynamicOps<T> ops, MapLike<T> input) {
            if (!(ops instanceof RegistryOps<T> registryOps)) {
                return DataResult.error(() -> "PlainsBorderChunkGenerator requires RegistryOps");
            }

            Optional<RegistryEntryLookup<Biome>> biomeLookup = registryOps.getEntryLookup(RegistryKeys.BIOME);
            RegistryEntry<Biome> plains = biomeLookup.orElseThrow().getOrThrow(BiomeKeys.PLAINS);

            return DataResult.success(new SmallWorldChunkGenerator(plains, Map.of(
                    CustomBiomeEnum.PLAINS, plains,
                    CustomBiomeEnum.SNOW, biomeLookup.orElseThrow().getOrThrow(BiomeKeys.SNOWY_PLAINS),
                    CustomBiomeEnum.DESERT, biomeLookup.orElseThrow().getOrThrow(BiomeKeys.DESERT),
                    CustomBiomeEnum.BEACH, biomeLookup.orElseThrow().getOrThrow(BiomeKeys.BEACH),
                    CustomBiomeEnum.OCEAN, biomeLookup.orElseThrow().getOrThrow(BiomeKeys.OCEAN)
            )));
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

    private SmallWorldChunkGenerator(RegistryEntry<Biome> plains, Map<CustomBiomeEnum, RegistryEntry<Biome>> biomes) {
        super(new FixedBiomeSource(plains));
        this.biomes = biomes;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    private CustomBiomeEnum getBiomeAt(BlockPos blockPos) {
        int x = blockPos.getX();
        int z = blockPos.getZ();
        double distance = Math.sqrt(x * x + z * z);

        if (distance < 128) return CustomBiomeEnum.PLAINS;

        double oceanBlend = oceanBlend(x, z);
        int surfaceHeight = getSurfaceHeight(x, z, CustomBiomeEnum.OCEAN);

        if (surfaceHeight < SEA_LEVEL && oceanBlend > 0.1) {
            return CustomBiomeEnum.OCEAN;
        }
        if (oceanBlend > 0.1 && surfaceHeight <= SEA_LEVEL + 2) {
            return CustomBiomeEnum.BEACH;
        }

        Map<CustomBiomeEnum, Double> weights = getBlendingWeights(x, z);
        weights.remove(CustomBiomeEnum.BEACH);
        weights.remove(CustomBiomeEnum.OCEAN);

        CustomBiomeEnum bestBiome = CustomBiomeEnum.PLAINS;
        double maxWeight = Double.NEGATIVE_INFINITY;
        for (Map.Entry<CustomBiomeEnum, Double> entry : weights.entrySet()) {
            if (entry.getValue() > maxWeight) {
                maxWeight = entry.getValue();
                bestBiome = entry.getKey();
            }
        }

        return bestBiome;
    }

    private Map<CustomBiomeEnum, Double> getBlendingWeights(int x, int z) {
        double tempScale = 512.0;
        double temp = tempMap.sample(x / tempScale, z / tempScale);

        double blendPower = 3.0;
        double epsilon = 0.0001;

        Map<CustomBiomeEnum, Double> weights = new EnumMap<>(CustomBiomeEnum.class);
        double totalWeight = 0.0;

        for (CustomBiomeBlendParams param : BIOME_BLEND_PARAMS) {
            if (param.biome == CustomBiomeEnum.BEACH || param.biome == CustomBiomeEnum.OCEAN) continue;

            double dist = Math.abs(param.temp - temp);
            double weight = 1.0 / Math.pow(dist + epsilon, blendPower);
            weights.put(param.biome, weight);
            totalWeight += weight;
        }

        for (CustomBiomeEnum biome : weights.keySet()) {
            weights.put(biome, weights.get(biome) / totalWeight);
        }

        return weights;
    }

    private CustomNoiseParams getBlendedNoiseParams(int x, int z) {
        Map<CustomBiomeEnum, Double> weights = getBlendingWeights(x, z);

        double scale1 = 0, scale2 = 0, amp1 = 0, amp2 = 0;
        for (CustomBiomeEnum biome : CustomBiomeEnum.values()) {
            double w = weights.getOrDefault(biome, 0.0);
            scale1 += biome.HILL_SCALE_1 * w;
            scale2 += biome.HILL_SCALE_2 * w;
            amp1   += biome.HILL_AMP_1 * w;
            amp2   += biome.HILL_AMP_2 * w;
        }

        return new CustomNoiseParams(scale1, scale2, amp1, amp2);
    }

    private void initialize(NoiseConfig noiseConfig) {
        if (initFinished) return;
        initFinished = true;

        // height
        Random rand = noiseConfig.getOrCreateRandomDeriver(HEIGHT_DERIVER_ID).split(HEIGHT_RANDOM_ID);
        heightNoise = new SimplexNoiseSampler(rand);

        // temp
        Random tempRand = noiseConfig.getOrCreateRandomDeriver(TEMP_DERIVER_ID).split(TEMP_RANDOM_ID);
        tempMap = new SimplexNoiseSampler(tempRand);

        Map<CustomBiomeEnum, Integer> counts = new HashMap<>();

        while (true) {
            for (int x = -RADIUS; x < RADIUS; x += 16) {
                for (int z = -RADIUS; z < RADIUS; z += 16) {
                    int surfaceHeight = getSurfaceHeight(x, z);

                    CustomBiomeEnum biome = getBiomeAt(new BlockPos(x, surfaceHeight, z));
                    counts.compute(biome, (bio, val) -> val == null ? 1 : val + 1);
                }
            }

            if (counts.getOrDefault(CustomBiomeEnum.SNOW, 0) > 0 && counts.getOrDefault(CustomBiomeEnum.DESERT, 0) > 0) {
                break;
            }

            TerraCraft.LOGGER.info("Regenerating temp map");

            counts.clear();
            tempMap = new SimplexNoiseSampler(tempRand);
        }
    }

    private int getSurfaceHeight(int x, int z) {
        return getSurfaceHeight(x, z, getBiomeAt(new BlockPos(x, SEA_LEVEL, z)));
    }

    private int getSurfaceHeight(int x, int z, CustomBiomeEnum biome) {
        CustomNoiseParams params = getBlendedNoiseParams(x, z);
        double n1 = heightNoise.sample(x * params.scale1, z * params.scale1);
        double n2 = heightNoise.sample(x * params.scale2, z * params.scale2);

        double inland = BASE_SURFACE_Y + (n1 * params.amp1) + (n2 * params.amp2);

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
        initialize(noiseConfig);
        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public CompletableFuture<Chunk> populateBiomes(NoiseConfig noiseConfig, Blender blender, StructureAccessor structureAccessor, Chunk chunk) {
        initialize(noiseConfig);

        ChunkPos cp = chunk.getPos();
        var sampler = noiseConfig.getMultiNoiseSampler();

        int startQuartX = cp.x << 2;
        int startQuartZ = cp.z << 2;

        for (int sectionY = chunk.getBottomSectionCoord(); sectionY < chunk.getTopSectionCoord(); sectionY++) {
            int startQuartY = sectionY << 2;
            ChunkSection section = chunk.getSection(chunk.sectionCoordToIndex(sectionY));

            section.populateBiomes((qx, qy, qz, s) -> {
                int wx = qx * 4 + 2;
                int wy = qy * 4 + 2;
                int wz = qz * 4 + 2;

                return biomes.get(getBiomeAt(new BlockPos(wx, wy, wz)));

            }, sampler, startQuartX, startQuartY, startQuartZ);
        }

        return CompletableFuture.completedFuture(chunk);
    }

    @Override
    public void carve(ChunkRegion chunkRegion, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess, StructureAccessor structureAccessor, Chunk chunk) {}

    @Override
    public void buildSurface(ChunkRegion region, StructureAccessor structureAccessor, NoiseConfig noiseConfig, Chunk chunk) {
        initialize(noiseConfig);

        BlockState bedrock = Blocks.BEDROCK.getDefaultState();
        BlockState stone = Blocks.STONE.getDefaultState();
        BlockState dirt = Blocks.DIRT.getDefaultState();
        BlockState grass = Blocks.GRASS_BLOCK.getDefaultState();
        BlockState sand = Blocks.SAND.getDefaultState();

        BlockPos.Mutable mpos = new BlockPos.Mutable();
        ChunkPos cpos = chunk.getPos();

        int startX = cpos.getStartX();
        int startZ = cpos.getStartZ();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = startX + lx;
                int wz = startZ + lz;

                int topY = getSurfaceHeight(wx, wz);

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

                CustomBiomeEnum biome = getBiomeAt(new BlockPos(wx, topY, wz));

                if (biome == CustomBiomeEnum.PLAINS || biome == CustomBiomeEnum.SNOW) {
                    mpos.set(wx, topY, wz);
                    chunk.setBlockState(mpos, grass, 0);
                }

                if (biome == CustomBiomeEnum.OCEAN) {
                    BlockState water = Blocks.WATER.getDefaultState();
                    mpos.set(wx, topY, wz);
                    chunk.setBlockState(mpos, sand, 0);

                    for (int y = topY + 1; y <= SEA_LEVEL; y++) {
                        mpos.set(wx, y, wz);
                        if (chunk.getBlockState(mpos).isAir()) {
                            chunk.setBlockState(mpos, water, 0);
                        }
                    }
                }

                if (biome == CustomBiomeEnum.BEACH) {
                    mpos.set(wx, topY, wz);
                    chunk.setBlockState(mpos, sand, 0);
                }

                if (biome == CustomBiomeEnum.DESERT) {
                    mpos.set(wx, topY, wz);
                    chunk.setBlockState(mpos, sand, 0);
                }

                if (biome != CustomBiomeEnum.PLAINS) continue;

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
    public void generateFeatures(StructureWorldAccess world, Chunk chunk, StructureAccessor structureAccessor) {
        ChunkPos cp = chunk.getPos();

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
    public void populateEntities(ChunkRegion region) {}

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
        initialize(noiseConfig);
        return getSurfaceHeight(x, z) + 1;
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        initialize(noiseConfig);

        BlockState[] states = new BlockState[WORLD_HEIGHT];

        BlockState bedrock = Blocks.BEDROCK.getDefaultState();
        BlockState stone = Blocks.STONE.getDefaultState();
        BlockState dirt = Blocks.DIRT.getDefaultState();
        BlockState grass = Blocks.GRASS_BLOCK.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();

        int topY = getSurfaceHeight(x, z);
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
        text.add("small_world radius=" + RADIUS);
    }
}
