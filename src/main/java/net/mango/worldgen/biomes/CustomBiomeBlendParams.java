package net.mango.worldgen.biomes;

public final class CustomBiomeBlendParams {
    public final CustomBiome biome;
    public final double temp;
    public final double humidity;
    public final double height;

    public CustomBiomeBlendParams(CustomBiome biome, double temp, double humidity, double height) {
        this.biome = biome;
        this.temp = temp;
        this.humidity = humidity;
        this.height = height;
    }
}
