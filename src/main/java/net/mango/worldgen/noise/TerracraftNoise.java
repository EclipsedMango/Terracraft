package net.mango.worldgen.noise;

public interface TerracraftNoise {
    double sample(double x, double y);

    default TerracraftNoise scale(double scale) {
        return (x, y) -> this.sample(x, y) * scale;
    }

    default TerracraftNoise offset(double offset) {
        return (x, y) -> this.sample(x, y) + offset;
    }

    default TerracraftNoise octavate(int octaves, double lacu, double gain) {
        return new OctavatedTerracraftNoise(this, octaves, lacu, gain);
    }

    default TerracraftNoise frequency(double freq) {
        return (x, y) -> this.sample(x * freq, y * freq);
    }
}
