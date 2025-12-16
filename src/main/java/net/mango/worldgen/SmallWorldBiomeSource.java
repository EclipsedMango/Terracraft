package net.mango.worldgen;

import com.mojang.serialization.*;
import net.mango.TerraCraft;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;

import java.util.stream.Stream;

import static net.mango.worldgen.SmallWorldParams.smoothstep;

public class SmallWorldBiomeSource extends BiomeSource {
    public static final int RADIUS = SmallWorldParams.RADIUS;

    private static final int SEA_LEVEL = SmallWorldParams.SEA_LEVEL;

    private static final int BASE_SURFACE_Y = SmallWorldParams.BASE_SURFACE_Y;
    private static final double HILL_SCALE_1 = SmallWorldParams.HILL_SCALE_1;
    private static final double HILL_SCALE_2 = SmallWorldParams.HILL_SCALE_2;
    private static final double HILL_AMP_1 = SmallWorldParams.HILL_AMP_1;
    private static final double HILL_AMP_2 = SmallWorldParams.HILL_AMP_2;

    private static final int BEACH_WIDTH = SmallWorldParams.BEACH_WIDTH;
    private static final int SLOPE_WIDTH = SmallWorldParams.SLOPE_WIDTH;
    private static final int OCEAN_FLOOR_Y = SmallWorldParams.OCEAN_FLOOR_Y;

    private static final long HEIGHT_SALT = 0x9E3779B97F4A7C15L;

    private static final Identifier BIOME_HEIGHT_DERIVER_ID = Identifier.of(TerraCraft.MOD_ID, "biome_height_deriver");
    private static final Identifier BIOME_HEIGHT_RANDOM_ID  = Identifier.of(TerraCraft.MOD_ID, "biome_height_random");

    public static final MapCodec<SmallWorldBiomeSource> CODEC = new MapCodec<>() {
        @Override
        public <T> DataResult<SmallWorldBiomeSource> decode(DynamicOps<T> ops, MapLike<T> input) {
            if (!(ops instanceof RegistryOps<T> registryOps)) {
                return DataResult.error(() -> "PlainsBorderBiomeSource requires RegistryOps");
            }

            var biomeLookup = registryOps.getEntryLookup(RegistryKeys.BIOME).orElseThrow();

            RegistryEntry<Biome> plains = biomeLookup.getOrThrow(BiomeKeys.PLAINS);
            RegistryEntry<Biome> beach  = biomeLookup.getOrThrow(BiomeKeys.BEACH);
            RegistryEntry<Biome> ocean  = biomeLookup.getOrThrow(BiomeKeys.OCEAN);

            // Seed will be applied later via withSeed(...)
            return DataResult.success(new SmallWorldBiomeSource(plains, beach, ocean, 0L));
        }

        @Override
        public <T> RecordBuilder<T> encode(SmallWorldBiomeSource value, DynamicOps<T> ops, RecordBuilder<T> builder) {
            // no config fields written
            return builder;
        }

        @Override
        public <T> Stream<T> keys(DynamicOps<T> ops) {
            return Stream.empty();
        }
    };

    private final RegistryEntry<Biome> plains;
    private final RegistryEntry<Biome> beach;
    private final RegistryEntry<Biome> ocean;

    private final long seed;
    private volatile SimplexNoiseSampler heightNoise;

    public SmallWorldBiomeSource(
            RegistryEntry<Biome> plains,
            RegistryEntry<Biome> beach,
            RegistryEntry<Biome> ocean,
            long seed
    ) {
        super();
        this.plains = plains;
        this.beach = beach;
        this.ocean = ocean;
        this.seed = seed;
    }

    @Override
    protected MapCodec<? extends BiomeSource> getCodec() {
        return CODEC;
    }

    @Override
    protected Stream<RegistryEntry<Biome>> biomeStream() {
        return Stream.of(plains, beach, ocean);
    }

    private void ensureNoise() {
        if (heightNoise != null) return;
        synchronized (this) {
            if (heightNoise != null) return;

            Random r = Random.create(seed ^ HEIGHT_SALT);
            heightNoise = new SimplexNoiseSampler(r);
        }
    }

    private int surfaceY(int x, int z) {
        ensureNoise();

        double n1 = heightNoise.sample(x * HILL_SCALE_1, z * HILL_SCALE_1);
        double n2 = heightNoise.sample(x * HILL_SCALE_2, z * HILL_SCALE_2);

        double inland = BASE_SURFACE_Y + (n1 * HILL_AMP_1) + (n2 * HILL_AMP_2);

        double oceanNoise = heightNoise.sample(x * 0.02, z * 0.02) * 2.0;
        double ocean = (OCEAN_FLOOR_Y + oceanNoise);

        double b = oceanBlend(x, z);
        double h = MathHelper.lerp(b, inland, ocean);

        return (int) Math.round(h);
    }

    private static double oceanBlend(int x, int z) {
        int d = Math.max(Math.abs(x), Math.abs(z));
        int start = RADIUS - BEACH_WIDTH;
        int end = RADIUS + SLOPE_WIDTH;
        return smoothstep((d - start) / (double) (end - start));
    }

    /**
     * NOTE: In modern versions, x/y/z here are *quart* coordinates (4-block steps).
     * We convert to block coords for our math.
     */
    @Override
    public RegistryEntry<Biome> getBiome(int x, int y, int z, MultiNoiseUtil.MultiNoiseSampler noise) {
        int wx = (x << 2) + 2;
        int wz = (z << 2) + 2;

        int topY = surfaceY(wx, wz);

        if (topY < SEA_LEVEL) {
            return ocean;
        }

        double b = oceanBlend(wx, wz);
        if (b > 0.10 && topY <= SEA_LEVEL + 2) {
            return beach;
        }

        return plains;
    }
}
