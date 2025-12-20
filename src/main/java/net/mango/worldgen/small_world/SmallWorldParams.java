package net.mango.worldgen.small_world;

import net.minecraft.util.math.MathHelper;

public class SmallWorldParams {
    private SmallWorldParams() {}

    public static final int RADIUS = 512;
    public static final int SEA_LEVEL = 50;
    public static final int PLAINS_CENTER_RAD = 128;

    public static final int WORLD_HEIGHT = 384;
    public static final int BASE_SURFACE_Y = 63;
    public static final int BEDROCK_Y = -64;

    public static final double HEIGHT_FREQ = 0.008;

    public static final int BEACH_WIDTH = 256;
    public static final int BEACH_DRY_HEIGHT = 100;
    public static final int SLOPE_WIDTH = 10;

    public static final double OCEAN_BLEND_MAX_THRESHOLD = 0.75;
    public static final double OCEAN_BLEND_MIN_THRESHOLD = 0.4;

    public static final int OCEAN_FLOOR_Y = SEA_LEVEL - 8;
    public static final int DEEP_OCEAN_FLOOR_Y = SEA_LEVEL - 16;

    public static double smoothstep(double t) {
        t = MathHelper.clamp(t, 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }
}
