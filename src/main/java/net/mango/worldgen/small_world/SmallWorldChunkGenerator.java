package net.mango.worldgen.small_world;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.RecordBuilder;
import net.mango.TerraCraft;
import net.mango.worldgen.biomes.TerracraftBiome;
import net.mango.worldgen.biomes.TerracraftBiomeBlendParams;
import net.mango.worldgen.noise.SimplexTerracraftNoise;
import net.mango.worldgen.noise.TerracraftNoise;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.*;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3i;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static net.mango.worldgen.small_world.SmallWorldParams.PLAINS_CENTER_RAD;

public class SmallWorldChunkGenerator extends ChunkGenerator {
    public static final int RADIUS = SmallWorldParams.RADIUS;

    private static final int MIN_Y = SmallWorldParams.BEDROCK_Y;
    private static final int WORLD_HEIGHT = SmallWorldParams.WORLD_HEIGHT;
    private static final int SEA_LEVEL = SmallWorldParams.SEA_LEVEL;

    private static final int BEDROCK_Y = SmallWorldParams.BEDROCK_Y;

    private static final Identifier PLANT_DERIVER_ID = Identifier.of(TerraCraft.MOD_ID, "plants_deriver");

    private static final Identifier TEMP_DERIVER_ID = Identifier.of(TerraCraft.MOD_ID, "temp_deriver");
    private static final Identifier TEMP_RANDOM_ID = Identifier.of(TerraCraft.MOD_ID, "temp_random");

    private static final Identifier HUMID_DERIVER_ID = Identifier.of(TerraCraft.MOD_ID, "humid_deriver");
    private static final Identifier HUMID_RANDOM_ID = Identifier.of(TerraCraft.MOD_ID, "humid_random");

    private static final Identifier COAST_DERIVER_ID = Identifier.of(TerraCraft.MOD_ID, "coast_deriver");
    private static final Identifier COAST_RANDOM_ID = Identifier.of(TerraCraft.MOD_ID, "coast_random");

    private final Map<TerracraftBiome, RegistryEntry<Biome>> biomes;

    private volatile boolean initFinished = false;

    private volatile TerracraftNoise tempMap;
    private volatile TerracraftNoise humidMap;

    private volatile TerracraftNoise coastline;

    private final Map<TerracraftBiome, TerracraftNoise> surfaceHeightMaps = new ConcurrentHashMap<>();
    private final Map<TerracraftBiome, TerracraftNoise> terrainHeightMaps = new ConcurrentHashMap<>();

    private static final List<TerracraftBiomeBlendParams> BIOME_BLEND_PARAMS = List.of(
            new TerracraftBiomeBlendParams(TerracraftBiome.PLAINS, 0.0, 0.2),
            new TerracraftBiomeBlendParams(TerracraftBiome.DESERT, 1.0, -0.5),
            new TerracraftBiomeBlendParams(TerracraftBiome.SNOW, -1.0, 0.3),
            new TerracraftBiomeBlendParams(TerracraftBiome.JUNGLE, 0.8, 0.8),
            new TerracraftBiomeBlendParams(TerracraftBiome.BEACH, 0.1, 0.1),
            new TerracraftBiomeBlendParams(TerracraftBiome.OCEAN, 0.0, 0.0)
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
                    TerracraftBiome.PLAINS, plains,
                    TerracraftBiome.SNOW, biomeLookup.orElseThrow().getOrThrow(BiomeKeys.SNOWY_PLAINS),
                    TerracraftBiome.DESERT, biomeLookup.orElseThrow().getOrThrow(BiomeKeys.DESERT),
                    TerracraftBiome.BEACH, biomeLookup.orElseThrow().getOrThrow(BiomeKeys.BEACH),
                    TerracraftBiome.OCEAN, biomeLookup.orElseThrow().getOrThrow(BiomeKeys.OCEAN),
                    TerracraftBiome.JUNGLE, biomeLookup.orElseThrow().getOrThrow(BiomeKeys.JUNGLE)
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

    private SmallWorldChunkGenerator(RegistryEntry<Biome> plains, Map<TerracraftBiome, RegistryEntry<Biome>> biomes) {
        super(new FixedBiomeSource(plains));
        this.biomes = biomes;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> getCodec() {
        return CODEC;
    }

    private TerracraftBiome getBiomeAt(BlockPos blockPos) {
        int x = blockPos.getX();
        int z = blockPos.getZ();

        if (blockPos.isWithinDistance(new Vec3i(0, blockPos.getY(), 0), PLAINS_CENTER_RAD)) {
            return TerracraftBiome.PLAINS;
        }

        Map<TerracraftBiome, Double> weights = getBlendingWeights(x, z);
        TerracraftBiome best = TerracraftBiome.PLAINS;
        double bestWeight = Double.NEGATIVE_INFINITY;

        for (var e : weights.entrySet()) {
            if (e.getValue() > bestWeight) {
                bestWeight = e.getValue();
                best = e.getKey();
            }
        }

        return best;
    }

    private Map<TerracraftBiome, Double> getBlendingWeights(int x, int z) {
        double tempScale = 512.0;
        double humidScale = 512.0;

        double temp = tempMap.sample(x / tempScale, z / tempScale);
        double humid = humidMap.sample(x / humidScale, z / humidScale);

        double blendPower = 10.0;
        double epsilon = 0.0001;

        Map<TerracraftBiome, Double> weights = new EnumMap<>(TerracraftBiome.class);
        double totalWeight = 0.0;

        for (TerracraftBiomeBlendParams param : BIOME_BLEND_PARAMS) {
            double distTemp = param.temp() - temp;
            double distHumid = param.humidity() - humid;

            double dist = Math.sqrt(distTemp * distTemp + distHumid * distHumid);

            double originDist = Math.max(Math.abs(x), Math.abs(z));
            double coastlineDist1 = coastline.sample(x, z) + originDist;
            double coastlineDist2 = coastline.sample(z + 128.0, x + 128.0) + originDist;

            if (param.biome() == TerracraftBiome.OCEAN) {
                dist += 6.5 - coastlineDist1 / 64.0;
            }

            if (param.biome() == TerracraftBiome.BEACH) {
                dist += 2.0 - coastlineDist2 / 192.0;
            }

            dist = Math.max(0, dist);

            double weight = 1.0 / Math.pow((dist + epsilon), blendPower);
            weights.put(param.biome(), weight);
            totalWeight += weight;
        }

        for (TerracraftBiome biome : weights.keySet()) {
            weights.put(biome, weights.get(biome) / totalWeight);
        }

        return weights;
    }

    private static class BiomeCheckStore {
        public int id;
        public TerracraftBiome biome;

        public BiomeCheckStore(TerracraftBiome biome) {
            this.biome = biome;
            this.id = -1;
        }
    }

    private record Vector2i(int x, int y) {
        public boolean equals(Object obj) {
            return this == obj || (obj instanceof Vector2i(int x1, int y1) && x == x1 && y == y1);
        }

        public Vector2i add(Vector2i other) {
            return new Vector2i(x + other.x, y + other.y);
        }
    }

    // this function is unused because it is supposed to be called by the debugger
    // will output a string that visualizes the current biome counter
    private String visualizePositions(Map<Vector2i, BiomeCheckStore> positions) {
        List<Character> chars = List.of(
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
            '[', '\\', ']', '^', '_', '`', '{', '|', '}', '~', '!', '"', '#',
            '$', '%', '&', '\'', '(', ')', '*', '+', ',', '-', '.', '/', ':',
            ';', '<', '=', '>', '?', '@'
        );

        StringBuilder output = new StringBuilder();

        for (int z = -RADIUS; z < RADIUS; z += 16) {
            for (int x = -RADIUS; x < RADIUS; x += 16) {
                int val = positions.get(new Vector2i(x, z)).id;
                if (val == -1) {
                    val = 0;
                }

                output.append(chars.get(val));
                output.append(positions.get(new Vector2i(x, z)).biome.name().charAt(0));
            }

            output.append("\n");
        }

        return output.toString();
    }

    private void initialize(NoiseConfig noiseConfig) {
        if (initFinished) return;
        initFinished = true;

        for (TerracraftBiome biome : TerracraftBiome.values()) {
            Identifier id = Identifier.of(TerraCraft.MOD_ID, biome.name().toLowerCase(Locale.ROOT));
            surfaceHeightMaps.put(biome, biome.surfaceHeightMap(noiseConfig.getOrCreateRandomDeriver(id).split(id)));
            terrainHeightMaps.put(biome, biome.terrainHeightMap(noiseConfig.getOrCreateRandomDeriver(id).split(id)));
        }

        int octaves = 4;
        double lacu = 2.0;
        double gain = 0.5;

        // temp
        Random tempRand = noiseConfig.getOrCreateRandomDeriver(TEMP_DERIVER_ID).split(TEMP_RANDOM_ID);
        tempMap = new SimplexTerracraftNoise(tempRand).octavate(octaves, lacu, gain);

        // humid
        Random humidRand = noiseConfig.getOrCreateRandomDeriver(HUMID_DERIVER_ID).split(HUMID_RANDOM_ID);
        humidMap = new SimplexTerracraftNoise(humidRand).octavate(octaves, lacu, gain);

        // coastline
        Random coastRand = noiseConfig.getOrCreateRandomDeriver(COAST_DERIVER_ID).split(COAST_RANDOM_ID);
        coastline = new SimplexTerracraftNoise(coastRand).scale(8.0).frequency(0.01);

        List<Vector2i> neighbors = List.of(new Vector2i(0, -16), new Vector2i(-16, 0), new Vector2i(0, 16), new Vector2i(16, 0));

        for (int retryCount = 0; ; retryCount++) {
            Map<Vector2i, BiomeCheckStore> positions = new HashMap<>();
            Map<TerracraftBiome, Integer> counts = new HashMap<>();

            for (int x = -RADIUS; x < RADIUS; x += 16) {
                for (int z = -RADIUS; z < RADIUS; z += 16) {
                    int surfaceHeight = getSurfaceHeight(x, z);

                    TerracraftBiome biome = getBiomeAt(new BlockPos(x, surfaceHeight, z));
                    positions.put(new Vector2i(x, z), new BiomeCheckStore(biome));
                }
            }

            if (0 == 0) {
                break;
            }

            // unique biome counter
            for (int x = -RADIUS; x < RADIUS; x += 16) {
                for (int z = -RADIUS; z < RADIUS; z += 16) {
                    Vector2i pos = new Vector2i(x, z);
                    BiomeCheckStore biome = positions.get(pos);
                    if (biome.id != -1) {
                        continue;
                    }

                    Set<BiomeCheckStore> connectedUnknowns = new HashSet<>();
                    List<Vector2i> open = new ArrayList<>();
                    connectedUnknowns.add(biome);
                    open.add(pos);

                    List<Vector2i> newOpen = new ArrayList<>();
                    while (!open.isEmpty()) {
                        for (Vector2i otherPos : open) {
                            for (Vector2i neighborPos : neighbors) {
                                neighborPos = otherPos.add(neighborPos);
                                BiomeCheckStore neighbor = positions.get(neighborPos);
                                if (neighbor == null ||
                                        neighbor.biome != biome.biome ||
                                        connectedUnknowns.contains(neighbor) ||
                                        neighbor.id != -1) {
                                    continue;
                                }

                                connectedUnknowns.add(neighbor);
                                newOpen.add(neighborPos);
                            }
                        }

                        // clear open and set open to newOpen while clearing newOpen
                        open.clear();
                        List<Vector2i> temp = open;
                        open = newOpen;
                        newOpen = temp;
                    }

                    int count = counts.compute(biome.biome, (k, v) -> v == null ? 1 : v + 1);
                    for (BiomeCheckStore connected : connectedUnknowns) {
                        connected.id = count;
                    }
                }
            }

            TerraCraft.LOGGER.info("Biome counts: {}", counts);
            if (counts.getOrDefault(TerracraftBiome.SNOW, 0) > 0 && counts.getOrDefault(TerracraftBiome.SNOW, 0) <= 2 && counts.getOrDefault(TerracraftBiome.DESERT, 0) > 0 && counts.getOrDefault(TerracraftBiome.JUNGLE, 0) > 0) {
                TerraCraft.LOGGER.info("Finished in {} attempts", retryCount + 1);
                break;
            }

            TerraCraft.LOGGER.info("Regenerating temp map");

            counts.clear();
            tempMap = new SimplexTerracraftNoise(tempRand).octavate(octaves, lacu, gain);
            humidMap = new SimplexTerracraftNoise(humidRand).octavate(octaves, lacu, gain);
        }
    }

    private int getSurfaceHeight(int x, int z) {
        Map<TerracraftBiome, Double> weights = getBlendingWeights(x, z);
        double height = 0;

        for (var entry : weights.entrySet()) {
            TerracraftBiome biome = entry.getKey();
            double weight = entry.getValue();

            if (weight < 0.01) continue;

            height += surfaceHeightMaps.get(biome).sample(x, z) * weight;
        }

        return (int) height;
    }

    private int getSurfaceDepth(int x, int z) {
        Map<TerracraftBiome, Double> weights = getBlendingWeights(x, z);
        double height = 0;

        for (var entry : weights.entrySet()) {
            TerracraftBiome biome = entry.getKey();
            double weight = entry.getValue();

            if (weight < 0.01) continue;

            height += terrainHeightMaps.get(biome).sample(x, z) * weight;
        }

        return (int) height;
    }

    private Random plantRandom(NoiseConfig noiseConfig, int x, int z) {
        RandomSplitter deriver = noiseConfig.getOrCreateRandomDeriver(PLANT_DERIVER_ID);
        long salt = ((long) x * 341873128712L) ^ ((long) z * 132897987541L);
        return deriver.split(salt);
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

        BlockPos.Mutable mpos = new BlockPos.Mutable();
        ChunkPos cpos = chunk.getPos();

        int startX = cpos.getStartX();
        int startZ = cpos.getStartZ();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int wx = startX + lx;
                int wz = startZ + lz;

                int topY = getSurfaceHeight(wx, wz);
                int topDepth = getSurfaceDepth(wx, wz);

                TerracraftBiome biome = getBiomeAt(new BlockPos(wx, topY, wz));

                for (int i = 0; i < topY - topDepth + 1; i++) {
                    mpos.set(wx, i, wz);
                    chunk.setBlockState(mpos, biome.terrainBlock().getDefaultState(), 0);
                }

                for (int i = 0; i < topDepth; i++) {
                    mpos.set(wx, topY - i, wz);
                    chunk.setBlockState(mpos, biome.surfaceBlock().getDefaultState(), 0);
                }

                if (biome == TerracraftBiome.OCEAN || biome == TerracraftBiome.BEACH) {
                    for (int i = topY; i < SmallWorldParams.SEA_LEVEL; i++) {
                        mpos.set(wx, i, wz);
                        chunk.setBlockState(mpos, Blocks.WATER.getDefaultState(), 0);
                    }
                }

                mpos.set(wx, topY, wz);
                chunk.setBlockState(mpos, biome.surfaceBlock().getDefaultState(), 0);

                mpos.set(wx, BEDROCK_Y, wz);
                chunk.setBlockState(mpos, Blocks.BEDROCK.getDefaultState(), 0);

//                if (biome != TerracraftBiome.PLAINS) continue;
//
//                int plantY = topY + 1;
//                if (!chunk.getBlockState(mpos).isOf(Blocks.GRASS_BLOCK)) continue;
//
//                Random pr = plantRandom(noiseConfig, wx, wz);
//
//                // density (example: 75% of blocks get a plant) without clumping
//                if (pr.nextInt(100) >= 75) continue;
//
//                // type (example: 80% grass, 20% non-grass)
//                boolean isGrass = pr.nextInt(100) < 80;
//                boolean tall = pr.nextInt(100) < 1;
//                boolean bush = pr.nextInt(100) < 60;
//
//                mpos.set(wx, plantY, wz);
//
//                if (!chunk.getBlockState(mpos).isAir()) continue;
//
//                if (tall) {
//                    mpos.set(wx, plantY + 1, wz);
//                    if (!chunk.getBlockState(mpos).isAir()) continue;
//
//                    BlockState lower = Blocks.TALL_GRASS.getDefaultState().with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
//                    BlockState upper = Blocks.TALL_GRASS.getDefaultState().with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
//
//                    mpos.set(wx, plantY, wz);
//                    chunk.setBlockState(mpos, lower, 0);
//
//                    mpos.set(wx, plantY + 1, wz);
//                    chunk.setBlockState(mpos, upper, 0);
//
//                    continue;
//                }
//
//                BlockState blockState = (isGrass) ? Blocks.SHORT_GRASS.getDefaultState() : (bush) ? Blocks.BUSH.getDefaultState() : Blocks.FERN.getDefaultState();
//                chunk.setBlockState(mpos, blockState, 0);
            }
        }
    }

    @Override
    public void generateFeatures(StructureWorldAccess world, Chunk chunk, StructureAccessor structureAccessor) {
        ChunkPos cp = chunk.getPos();

        Registry<PlacedFeature> placed = world.getRegistryManager().getOrThrow(RegistryKeys.PLACED_FEATURE);
        PlacedFeature oakChecked = placed.get(TreePlacedFeatures.OAK_CHECKED);
        PlacedFeature jungleChecked = placed.get(TreePlacedFeatures.JUNGLE_TREE);
        if (oakChecked == null || jungleChecked == null) return;

        long s = world.getSeed() ^ (cp.x * 341873128712L) ^ (cp.z * 132897987541L) ^ 0x6A09E667F3BCC909L;
        Random rand = Random.create(s);

        int attempts = 1 + rand.nextInt(2);
        for (int i = 0; i < attempts; i++) {
            int x = cp.getStartX() + rand.nextInt(16);
            int z = cp.getStartZ() + rand.nextInt(16);

            int y = world.getTopY(Heightmap.Type.WORLD_SURFACE_WG, x, z);
            BlockPos origin = new BlockPos(x, y, z);

            if (getBiomeAt(origin) == TerracraftBiome.JUNGLE) {
                jungleChecked.generate(world, this, rand, origin);
                continue;
            }

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
