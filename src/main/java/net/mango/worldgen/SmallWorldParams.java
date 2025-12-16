package net.mango.worldgen;

import net.minecraft.util.math.MathHelper;

public class SmallWorldParams {
    private SmallWorldParams() {}

    public static final int RADIUS = 512;
    public static final int SEA_LEVEL = 50;

    public static final int BASE_SURFACE_Y = 63;
    public static final double HILL_SCALE_1 = 0.008;
    public static final double HILL_SCALE_2 = 0.02;
    public static final double HILL_AMP_1 = 5.0;
    public static final double HILL_AMP_2 = 2.0;

    public static final int BEACH_WIDTH = 256;
    public static final int SLOPE_WIDTH = 10;
    public static final int OCEAN_FLOOR_Y = SEA_LEVEL - 8;

    public static double smoothstep(double t) {
        t = MathHelper.clamp(t, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
}
