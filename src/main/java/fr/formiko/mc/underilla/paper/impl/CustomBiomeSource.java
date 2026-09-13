package fr.formiko.mc.underilla.paper.impl;

import fr.formiko.mc.underilla.paper.Underilla;
import fr.formiko.mc.underilla.paper.io.UnderillaConfig;
import fr.formiko.mc.underilla.paper.io.UnderillaConfig.BooleanKeys;
import fr.formiko.mc.underilla.paper.io.UnderillaConfig.IntegerKeys;
import fr.formiko.mc.underilla.paper.io.UnderillaConfig.SetBiomeStringKeys;
import fr.formiko.mc.underilla.paper.io.UnderillaConfig.StringKeys;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Biome;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.generator.BiomeParameterPoint;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

public class CustomBiomeSource {
    private volatile BiomeProvider vanillaBiomeSource;
    private SampledVanillaBiomes sampledVanillaBiomes;
    private final BukkitWorldReader worldSurfaceReader;
    private final BukkitWorldReader worldCavesReader;
    private final Map<String, LongAdder> biomesPlaced = new ConcurrentHashMap<>();
    private final Map<String, Selection> surfaceBiomes = new ConcurrentHashMap<>();
    private final Map<Biome, Selection> caveBiomes = new ConcurrentHashMap<>();
    private final ThreadLocal<QueryCache> queries = ThreadLocal.withInitial(QueryCache::new);
    private long lastInfoPrinted = 0;
    private long lastWarnningPrinted = 0;
    private final boolean debugEnabled;

    public CustomBiomeSource(@Nonnull BukkitWorldReader worldSurfaceReader, @Nullable BukkitWorldReader worldCavesReader) {
        this.worldSurfaceReader = worldSurfaceReader;
        this.worldCavesReader = worldCavesReader;
        this.debugEnabled = Underilla.getUnderillaConfig().getBoolean(BooleanKeys.DEBUG);
    }

    public Map<String, Long> getBiomesPlaced() {
        Map<String, Long> result = new HashMap<>();
        biomesPlaced.forEach((key, count) -> result.put(key, count.sum()));
        return result;
    }

    /**
     * Get biome at x, y, z.
     * 
     * @param worldInfo World information.
     * @param x         Actual world coordinate.
     * @param y         Actual world coordinate.
     * @param z         Actual world coordinate.
     * @return
     */
    public Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z) {
        return getBiome(worldInfo, x, y, z, null);
    }

    public Biome getBiome(@NotNull WorldInfo worldInfo, int x, int y, int z, @Nullable BiomeParameterPoint point) {
        UnderillaConfig config = Underilla.getUnderillaConfig();
        BiomeProvider vanilla = vanillaBiomeSource;
        if (vanilla == null) vanilla = initializeVanilla(config);
        QueryCache cache = queries.get();
        cache.bind(worldInfo, config, config.getRevision());
        int slot = QueryCache.slot(x, y, z);
        Selection selected = vanilla == null ? null : cache.get(slot, x, y, z);
        if (selected == null) {
            selected = selectBiome(worldInfo, x, y, z, vanilla, config, point);
            // During world initialization the vanilla provider is not available yet.
            // Do not retain provisional surface-only answers from that phase.
            if (vanilla != null) cache.put(slot, x, y, z, selected);
        }
        selected.count.increment();
        if (debugEnabled) debug("Use " + selected.key + " at " + x + " " + y + " " + z);
        return selected.biome;
    }

    private synchronized BiomeProvider initializeVanilla(UnderillaConfig config) {
        if (vanillaBiomeSource == null) {
            CraftWorld world = (CraftWorld) Bukkit.getWorld(config.getString(StringKeys.FINAL_WORLD_NAME));
            if (world != null) {
                BiomeProvider provider = world.vanillaBiomeProvider();
                sampledVanillaBiomes = SampledVanillaBiomes.from(world, provider);
                vanillaBiomeSource = provider;
            }
        }
        return vanillaBiomeSource;
    }

    private Selection selectBiome(WorldInfo worldInfo, int x, int y, int z, BiomeProvider vanilla, UnderillaConfig config,
            BiomeParameterPoint point) {
        // Needed to get surface biome & test if caves biome will override a preserved biome.
        // Use the top biome from the surface world only if configured.
        int surfaceWorldBiomeY = config.getBoolean(BooleanKeys.SURFACE_WORLD_BIOME_USE_TOP_Y_VALUE_ONLY)
                ? config.getInt(IntegerKeys.GENERATION_AREA_MAX_Y)
                : y;
        String surfaceWorldBiomeName = worldSurfaceReader.getBiomeName(x, surfaceWorldBiomeY, z);

        if (vanilla != null && surfaceWorldBiomeName != null
                && !config.getSetBiomeString(SetBiomeStringKeys.BIOME_MERGING_FROM_CAVES_GENERATION_ONLY_ON_BIOMES).isEmpty() && !config
                .isBiomeInSet(SetBiomeStringKeys.SURFACE_WORLD_ONLY_ON_THIS_BIOMES, surfaceWorldBiomeName)) {
            Biome vanillaBiome = sampledVanillaBiomes == null ? null : sampledVanillaBiomes.getBiome(vanilla, worldInfo, point);
            if (vanillaBiome == null) vanillaBiome = vanilla.getBiome(worldInfo, x, y, z);
            String vanillaBiomeName = vanillaBiome.getKey().asString();
            // info("Currently tested vanillaBiome: " + vanillaBiomeName + " at " + x + " " + y + " " + z);
            // If is a cave biome that we should preserve & is below the surface of surface world.
            if (vanillaBiomeName != null && config
                    .isBiomeInSet(SetBiomeStringKeys.BIOME_MERGING_FROM_CAVES_GENERATION_ONLY_ON_BIOMES, vanillaBiomeName)
                    && isUnderSurface(worldSurfaceReader, x, y, z)) {
                return caveBiomes.computeIfAbsent(vanillaBiome, biome -> selection("cavesGeneration:" + vanillaBiomeName, biome));
            }
        }

        // // Get biome from cave world if it's in the list of transferWorldFromCavesWorld.
        // // & surface biome does not have a preserved biome here.
        // // & it's below the surface.
        // if (Underilla.CONFIG.transferBiomesFromCavesWorld && worldCavesReader != null
        // && (surfaceWorldBiome == null || !Underilla.CONFIG.preserveBiomes.contains(surfaceWorldBiome.getName()))
        // && y < Underilla.CONFIG.mergeLimit - Underilla.CONFIG.mergeDepth && y < 50) {
        // // For now there is as 50 hard max limits.
        // BukkitBiome cavesWorldBiome = (BukkitBiome) worldCavesReader.biomeAt(x, y, z).orElse(null);
        // if (cavesWorldBiome != null && Underilla.CONFIG.transferCavesWorldBiomes.contains(cavesWorldBiome.getName())) {
        // info("Use cavesWorldBiome because it's a transferedCavesWorldBiomes: " + cavesWorldBiome.getName() + " at " + x + " " + y
        // + " " + z);
        // String key = "caves:" + cavesWorldBiome.getName();
        // biomesPlaced.put(key, biomesPlaced.getOrDefault(key, 0L) + 1);
        // return cavesWorldBiome.getBiome();
        // }
        // }

        // Get biome from surface world.
        if (surfaceWorldBiomeName != null) {
            return surfaceBiomes.computeIfAbsent(surfaceWorldBiomeName, name -> selection("surface:" + name,
                    BukkitBiome.getBiomeRegistryAccess().get(NamespacedKey.fromString(name))));
        }

        // If no other biome found, use vanilla biome.
        warning("Use vanilla because no other biome found at " + x + " " + y + " " + z);
        String key = "error:" + BukkitBiome.DEFAULT.getName();
        return selection(key, BukkitBiome.DEFAULT.getBiome());
    }

    private Selection selection(String key, Biome biome) {
        return new Selection(biome, key, biomesPlaced.computeIfAbsent(key, ignored -> new LongAdder()));
    }

    record Selection(Biome biome, String key, LongAdder count) {}

    /** Fixed-size, allocation-free lookup with exact coordinates, private to each worker. */
    static final class QueryCache {
        private static final int SIZE = 8192;
        private final int[] xs = new int[SIZE], ys = new int[SIZE], zs = new int[SIZE];
        private final Selection[] values = new Selection[SIZE];
        private WorldInfo world;
        private UnderillaConfig config;
        private long revision;

        void bind(WorldInfo world, UnderillaConfig config, long revision) {
            if (this.world != world || this.config != config || this.revision != revision) {
                Arrays.fill(values, null);
                this.world = world;
                this.config = config;
                this.revision = revision;
            }
        }
        static int slot(int x, int y, int z) {
            int hash = x * 73428767 ^ y * 912931 ^ z * 19349663;
            hash ^= hash >>> 16;
            hash *= 0x7feb352d;
            return (hash ^ (hash >>> 15)) & (SIZE - 1);
        }
        Selection get(int slot, int x, int y, int z) {
            return xs[slot] == x && ys[slot] == y && zs[slot] == z ? values[slot] : null;
        }
        void put(int slot, int x, int y, int z, Selection value) {
            xs[slot] = x; ys[slot] = y; zs[slot] = z; values[slot] = value;
        }
    }

    private boolean isUnderSurface(BukkitWorldReader worldSurfaceReader, int x, int y, int z) {
        if (Underilla.getUnderillaConfig().getBoolean(BooleanKeys.BIOME_MERGING_FROM_CAVES_GENERATION_ONLY_UNDER_SURFACE)) {
            // Merging biome below the surface only.
            x = Math.floorDiv(x, 4) * 4;
            z = Math.floorDiv(z, 4) * 4;
            for (int i = 0; i < 4; i++) {
                for (int j = 0; j < 4; j++) {
                    // If the block is over the merge limit, it's not under the surface.
                    if (y >= topYOfSurfaceWorld(worldSurfaceReader, x + i, z + j)) {
                        return false;
                    }
                }
            }
        }
        // All blocks are under the surface (or it's configured not to check).
        return true;
    }

    private int topYOfSurfaceWorld(BukkitWorldReader worldSurfaceReader, int x, int z) {
        return worldSurfaceReader.getLowerBlockOfSurfaceWorldYLevel(x, z);
    }

    private synchronized void debug(String message) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastInfoPrinted > Underilla.MS_PER_SECOND) {
            Underilla.debug(message);
            lastInfoPrinted = currentTime;
        }
    }
    private synchronized void info(String message) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastInfoPrinted > Underilla.MS_PER_SECOND) {
            Underilla.info(message);
            lastInfoPrinted = currentTime;
        }
    }
    private synchronized void warning(String message) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastWarnningPrinted > Underilla.MS_PER_SECOND) {
            Underilla.warning(message);
            lastWarnningPrinted = currentTime;
        }
    }
}
