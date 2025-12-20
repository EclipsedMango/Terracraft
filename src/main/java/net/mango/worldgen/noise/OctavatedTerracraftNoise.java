package net.mango.worldgen.noise;

import net.minecraft.util.math.random.Random;

public class OctavatedTerracraftNoise implements TerracraftNoise {
    TerracraftNoise baseNoise;

    int octaves = 8;
    double lacunarity = 2;
    double gain = 0.5;

    public OctavatedTerracraftNoise(TerracraftNoise baseNoise, int octaves, double lacunarity, double gain) {
        this.baseNoise = baseNoise;
        this.octaves = octaves;
        this.lacunarity = lacunarity;
        this.gain = gain;
    }

    public OctavatedTerracraftNoise(int octaves, double lacunarity, double gain) {
        this(new SimplexTerracraftNoise(Random.create()), octaves, lacunarity, gain);
    }

    @Override
    public double sample(double x, double y) {
        double total = 0;
        double scope = 0.0;

        double freq = 1.0;
        double amp = 1.0;

        for (int i = 0; i < octaves; i++) {
            total += baseNoise.sample(x * freq, y * freq) * amp;
            scope += amp;

            freq *= lacunarity;
            amp *= gain;
        }

        return total / scope;
    }
}
