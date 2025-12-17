package net.mango.worldgen;

public final class CustomBiomeBlendParams {
    final CustomBiomeEnum biome;
    final double temp;
    final double idealDistance;

    public CustomBiomeBlendParams(CustomBiomeEnum biome, double temp, double idealDistance) {
        this.biome = biome;
        this.temp = temp;
        this.idealDistance = idealDistance;
    }
}
