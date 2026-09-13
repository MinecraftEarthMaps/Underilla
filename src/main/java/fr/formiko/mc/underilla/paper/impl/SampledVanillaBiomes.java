package fr.formiko.mc.underilla.paper.impl;

import java.util.UUID;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.bukkit.block.Biome;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBiome;
import org.bukkit.craftbukkit.generator.CraftBiomeParameterPoint;
import org.bukkit.craftbukkit.generator.CustomChunkGenerator;
import org.bukkit.craftbukkit.generator.CustomWorldChunkManager;
import org.bukkit.generator.BiomeParameterPoint;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

/** Reuses the sample already supplied by Paper, retaining the normal provider as fallback. */
final class SampledVanillaBiomes {
    private final BiomeProvider provider;
    private final UUID worldId;
    private final MultiNoiseBiomeSource source;

    private SampledVanillaBiomes(BiomeProvider provider, UUID worldId, MultiNoiseBiomeSource source) {
        this.provider = provider;
        this.worldId = worldId;
        this.source = source;
    }

    static SampledVanillaBiomes from(CraftWorld world, BiomeProvider provider) {
        var generator = world.getHandle().getChunkSource().getGenerator();
        if (generator instanceof CustomChunkGenerator custom) generator = custom.getDelegate();
        var source = generator.getBiomeSource();
        if (source instanceof CustomWorldChunkManager custom) source = custom.vanillaBiomeSource;
        return source instanceof MultiNoiseBiomeSource multiNoise
                ? new SampledVanillaBiomes(provider, world.getUID(), multiNoise) : null;
    }

    Biome getBiome(BiomeProvider provider, WorldInfo world, BiomeParameterPoint point) {
        if (this.provider != provider || !worldId.equals(world.getUID())
                || point == null || point.getClass() != CraftBiomeParameterPoint.class) return null;
        long temperature = ClimateCoordinates.recover(point.getTemperature());
        long humidity = ClimateCoordinates.recover(point.getHumidity());
        long continentalness = ClimateCoordinates.recover(point.getContinentalness());
        long erosion = ClimateCoordinates.recover(point.getErosion());
        long depth = ClimateCoordinates.recover(point.getDepth());
        long weirdness = ClimateCoordinates.recover(point.getWeirdness());
        if (temperature == ClimateCoordinates.UNSUPPORTED || humidity == ClimateCoordinates.UNSUPPORTED
                || continentalness == ClimateCoordinates.UNSUPPORTED || erosion == ClimateCoordinates.UNSUPPORTED
                || depth == ClimateCoordinates.UNSUPPORTED || weirdness == ClimateCoordinates.UNSUPPORTED) return null;
        return CraftBiome.minecraftHolderToBukkit(source.getNoiseBiome(
                new Climate.TargetPoint(temperature, humidity, continentalness, erosion, depth, weirdness)));
    }
}
