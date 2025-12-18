package net.mango.worldgen;

public enum CustomBiome {
    PLAINS(0.008, 0.02, 5.0, 2.0),
    DESERT(0.004, 0.02, 3.0, 2.0),
    SNOW(0.01, 0.02, 12.0, 8.0),
    BEACH(0.007, 0.02, 4.0, 2.0),
    OCEAN(0.007, 0.02, 4.0, 2.0),
    JUNGLE(0.01, 0.02, 30.0, 1.0);

    final double HILL_SCALE_1;
    final double HILL_SCALE_2;

    final double HILL_AMP_1;
    final double HILL_AMP_2;

    CustomBiome(double hillScale1, double hillScale2, double hillAmp1, double hillAmp2) {
        HILL_SCALE_1 = hillScale1;
        HILL_SCALE_2 = hillScale2;
        HILL_AMP_1 = hillAmp1;
        HILL_AMP_2 = hillAmp2;
    }
}
