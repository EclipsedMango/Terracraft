package net.mango.worldgen.noise;

import net.minecraft.util.math.noise.SimplexNoiseSampler;
import net.minecraft.util.math.random.Random;

public class SimplexTerracraftNoise implements TerracraftNoise {
    SimplexNoiseSampler sampler;
    
    SimplexTerracraftNoise(SimplexNoiseSampler sampler) {
        this.sampler = sampler;
    }

    public SimplexTerracraftNoise(Random random) {
        this(new SimplexNoiseSampler(random));
    }
    
    @Override
    public double sample(double x, double y) {
        return sampler.sample(x, y);
    }
}
