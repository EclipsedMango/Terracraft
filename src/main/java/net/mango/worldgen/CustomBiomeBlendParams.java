package net.mango.worldgen;

public final class CustomBiomeBlendParams {
    final CustomBiome biome;
    final double temp;
    final double humidity;
    final double height;

    public CustomBiomeBlendParams(CustomBiome biome, double temp, double humidity, double height) {
        this.biome = biome;
        this.temp = temp;
        this.humidity = humidity;
        this.height = height;
    }
}
