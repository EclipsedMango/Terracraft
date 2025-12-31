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
import net.minecraft.util.math.random.Random;
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

public class SmallWorldChunkGenerator extends ChunkGenerator {
    private static final Identifier TEMP_DERIVER_ID = Identifier.of(TerraCraft.MOD_ID, "temp_deriver");
    private static final Identifier HUMID_DERIVER_ID = Identifier.of(TerraCraft.MOD_ID, "humid_deriver");
    private static final Identifier COAST_DERIVER_ID = Identifier.of(TerraCraft.MOD_ID, "coast_deriver");

    private final Map<TerracraftBiome, RegistryEntry<Biome>> biomes;

    private volatile TerracraftNoise tempMap;
    private volatile TerracraftNoise humidMap;
    private volatile TerracraftNoise coastline;

    private volatile boolean initFinished = false;

    private volatile Random random;

    private Set<Vector2i> snowCells = new HashSet<>();
    private Set<Vector2i> desertCells = new HashSet<>();
    private Set<Vector2i> jungleCells = new HashSet<>();

    private final int beachCellDistance = 5;
    private final int plainsRad = 2;

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

    private record Vector2i(int x, int y) {
        public boolean equals(Object obj) {
            return this == obj || (obj instanceof Vector2i(int x1, int y1) && x == x1 && y == y1);
        }

        public Vector2i add(Vector2i other) {
            return new Vector2i(x + other.x, y + other.y);
        }
    }

    private Vector2i randomCellPos(Random random) {
        while (true) {
            int x = random.nextInt(beachCellDistance * 2 + 1) - beachCellDistance;
            int z = random.nextInt(beachCellDistance * 2 + 1) - beachCellDistance;

            int dist = Math.max(Math.abs(x), Math.abs(z));
            if (dist > plainsRad && dist <= beachCellDistance) {
                return new Vector2i(x, z);
            }
        }
    }

    private boolean isNotValidBiomeCell(Vector2i cell) {
        int dist = Math.max(Math.abs(cell.x), Math.abs(cell.y));

        if (dist <= plainsRad) return true;
        return dist > beachCellDistance;
    }

    private boolean isOccupied(Vector2i cell) {
        return snowCells.contains(cell) || desertCells.contains(cell) || jungleCells.contains(cell);
    }

    private Vector2i[] fisherYatesShuffle(Vector2i pos) {
        // starts from the right and goes clockwise
        Vector2i[] neighbors = {
                new Vector2i(pos.x + 1, pos.y),
                new Vector2i(pos.x + 1, pos.y - 1),
                new Vector2i(pos.x, pos.y - 1),
                new Vector2i(pos.x - 1, pos.y - 1),
                new Vector2i(pos.x - 1, pos.y),
                new Vector2i(pos.x - 1, pos.y + 1),
                new Vector2i(pos.x, pos.y + 1),
                new Vector2i(pos.x + 1, pos.y + 1),
        };

        // fisher-yates shuffle
        for (int i = neighbors.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            Vector2i t = neighbors[i];
            neighbors[i] = neighbors[j];
            neighbors[j] = t;
        }

        return neighbors;
    }

    private Vector2i findSpotNearPos(Vector2i pos, int rad) {
        Vector2i[] neighbors = {
                new Vector2i(pos.x + rad, pos.y),
                new Vector2i(pos.x, pos.y - rad),
                new Vector2i(pos.x - rad, pos.y),
                new Vector2i(pos.x, pos.y + rad),
        };

        for (Vector2i neighbor : neighbors) {
            if (!isOccupied(neighbor)) return neighbor;
        }

        return new Vector2i(0, 0);
    }

    private Set<Vector2i> growRegionAvoiding(Vector2i pos, int targetSize, Set<Vector2i> forbidden) {
        Set<Vector2i> region = new HashSet<>();
        ArrayDeque<Vector2i> frontier = new ArrayDeque<>();

        region.add(pos);
        frontier.add(pos);

        while (!frontier.isEmpty() && region.size() < targetSize) {
            Vector2i current = frontier.poll();

            for (Vector2i n : fisherYatesShuffle(current)) {
                if (region.size() >= targetSize) break;
                if (region.contains(n)) continue;
                if (forbidden.contains(n)) continue;
                if (isNotValidBiomeCell(n)) continue;

                region.add(n);
                frontier.add(n);
            }
        }

        return region;
    }

    void generateInitialBiomes(NoiseConfig noiseConfig) {
        random = noiseConfig.getOrCreateRandomDeriver(Identifier.of(TerraCraft.MOD_ID, "biome_supercell")).split("biome_supercell");
        TerraCraft.LOGGER.info("random number = {}", random.nextInt());

        Vector2i snowPos = randomCellPos(random);
        Vector2i desertPos = new Vector2i(-snowPos.x, -snowPos.y);
        Vector2i junglePos = findSpotNearPos(desertPos, 2);

        Set<Vector2i> forbidden = new HashSet<>();

        snowCells = growRegionAvoiding(snowPos, 13, forbidden);
        desertCells = growRegionAvoiding(desertPos, 10, forbidden);

        forbidden.addAll(snowCells);
        forbidden.addAll(desertCells);

        jungleCells = growRegionAvoiding(junglePos, 14, forbidden);
    }

    private void initialize(NoiseConfig noiseConfig) {
        if (initFinished) return;
        initFinished = true;

        generateInitialBiomes(noiseConfig);
    }

    private TerracraftBiome getBiomeAt(BlockPos blockPos) {
        int x = blockPos.getX();
        int z = blockPos.getZ();

        int cellOffset = 64;
        int halfCell = cellOffset / 2;

        Vector2i cellPos = new Vector2i(Math.floorDiv(x + halfCell, cellOffset), Math.floorDiv(z + halfCell, cellOffset));
        int cellDistance = Math.max(Math.abs(cellPos.x), Math.abs(cellPos.y));

        if (cellDistance <= plainsRad) {
            return TerracraftBiome.PLAINS;
        }

        if (cellDistance > beachCellDistance) {
            return (cellDistance > beachCellDistance + 1) ? TerracraftBiome.OCEAN : TerracraftBiome.BEACH;
        }

        if (snowCells.contains(cellPos)) {
            return TerracraftBiome.SNOW;
        }

        if (desertCells.contains(cellPos)) {
            return TerracraftBiome.DESERT;
        }

        if (jungleCells.contains(cellPos)) {
            return TerracraftBiome.JUNGLE;
        }

        return TerracraftBiome.PLAINS;
    }

    private int getSurfaceHeight(int x, int z, boolean surface) {
//        Map<TerracraftBiome, Double> weights = getBlendingWeights(x, z);
        double height = 0;

//        for (var entry : weights.entrySet()) {
//            if (surface) {
//                height += surfaceHeightMaps.get(entry.getKey()).sample(x, z) * entry.getValue();
//            } else {
//                height += terrainHeightMaps.get(entry.getKey()).sample(x, z) * entry.getValue();
//            }
//        }

        return (int) height;
    }

    @Override
    protected MapCodec<? extends ChunkGenerator> getCodec() {
        return CODEC;
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

                int topY = getSurfaceHeight(wx, wz, true);
                int topDepth = getSurfaceHeight(wx, wz, false);

                TerracraftBiome biome = getBiomeAt(new BlockPos(wx, topY, wz));

                mpos.set(wx, 64, wz);
                chunk.setBlockState(mpos, biome.surfaceBlock().getDefaultState(), 0);

                mpos.set(wx, 63, wz);
                chunk.setBlockState(mpos, Blocks.BLACK_STAINED_GLASS.getDefaultState(), 0);

                mpos.set(wx, SmallWorldParams.BEDROCK_Y, wz);
                chunk.setBlockState(mpos, Blocks.BEDROCK.getDefaultState(), 0);

                if (Math.floorMod(wx + 32, 64) == 0 && Math.floorMod(wz + 32, 64) == 0) {
                    mpos.set(wx, 65, wz);
                    chunk.setBlockState(mpos, Blocks.RED_WOOL.getDefaultState(), 0);
                }
            }
        }
    }

    @Override
    public void generateFeatures(StructureWorldAccess world, Chunk chunk, StructureAccessor structureAccessor) {
        if (1 == 1) {
            return;
        }

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
        return SmallWorldParams.WORLD_HEIGHT;
    }

    @Override
    public int getSeaLevel() {
        return SmallWorldParams.SEA_LEVEL;
    }

    @Override
    public int getMinimumY() {
        return SmallWorldParams.BEDROCK_Y;
    }

    @Override
    public int getHeight(int x, int z, Heightmap.Type heightmap, HeightLimitView world, NoiseConfig noiseConfig) {
        initialize(noiseConfig);
        return getSurfaceHeight(x, z, true) + 1;
    }

    @Override
    public VerticalBlockSample getColumnSample(int x, int z, HeightLimitView world, NoiseConfig noiseConfig) {
        initialize(noiseConfig);

        BlockState[] states = new BlockState[SmallWorldParams.WORLD_HEIGHT];

        BlockState bedrock = Blocks.BEDROCK.getDefaultState();
        BlockState stone = Blocks.STONE.getDefaultState();
        BlockState dirt = Blocks.DIRT.getDefaultState();
        BlockState grass = Blocks.GRASS_BLOCK.getDefaultState();
        BlockState air = Blocks.AIR.getDefaultState();

        int topY = getSurfaceHeight(x, z, true);
        int stoneTop = Math.max(SmallWorldParams.BEDROCK_Y + 1, topY - 4);

        for (int i = 0; i < SmallWorldParams.WORLD_HEIGHT; i++) {
            int y = SmallWorldParams.BEDROCK_Y + i;

            BlockState s;
            if (y == SmallWorldParams.BEDROCK_Y) s = bedrock;
            else if (y <= stoneTop) s = stone;
            else if (y < topY) s = dirt;
            else if (y == topY) s = grass;
            else if (y <= SmallWorldParams.SEA_LEVEL) s = Blocks.WATER.getDefaultState();
            else s = air;

            states[i] = s;
        }

        return new VerticalBlockSample(SmallWorldParams.BEDROCK_Y, states);
    }

    @Override
    public void appendDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
        text.add("small_world radius=" + SmallWorldParams.RADIUS);
        text.add("weight at:" + pos.getX() + " / " + pos.getZ() + " = " + tempMap.sample((double) pos.getX() / SmallWorldParams.RADIUS, (double) pos.getZ() / SmallWorldParams.RADIUS));
    }
}
