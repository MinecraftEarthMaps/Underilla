package fr.formiko.mc.underilla.core.reader;

import com.jkantrell.mca.Chunk;
import com.jkantrell.mca.MCAUtil;
import fr.formiko.mc.underilla.core.api.Biome;
import fr.formiko.mc.underilla.core.api.Block;
import fr.formiko.mc.underilla.core.generation.MergeStrategy;
import fr.formiko.mc.underilla.paper.Underilla;
import fr.formiko.mc.underilla.paper.impl.BukkitBlock;
import fr.formiko.mc.underilla.paper.io.UnderillaConfig.IntegerKeys;
import fr.formiko.mc.underilla.paper.io.UnderillaConfig.SetBiomeStringKeys;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public abstract class WorldReader implements Reader {

    // CONSTANTS
    private static final String REGION_DIRECTORY = "region";

    // FIELDS
    private final File world_;
    private final File regions_;
    private final RLUCache<Optional<ChunkReader>> chunkCache_;
    private final RLUCache<Integer> yLevelCache_;
    private final RLUCacheTriple<String> biomeCache_;
    private final Object[] chunkLoadLocks = new Object[256];

    // CONSTRUCTORS
    protected WorldReader(String worldPath) throws NoSuchFieldException { this(new File(worldPath)); }
    protected WorldReader(String worldPath, int cacheSize) throws NoSuchFieldException { this(new File(worldPath), cacheSize); }
    protected WorldReader(File worldDir) throws NoSuchFieldException {
        this(worldDir, Underilla.getUnderillaConfig().getInt(IntegerKeys.CACHE_SIZE));
    }
    protected WorldReader(File worldDir, int cacheSize) throws NoSuchFieldException {
        if (!(worldDir.exists() && worldDir.isDirectory())) {
            throw new NoSuchFieldException("World directory '" + worldDir.getPath() + "' does not exist.");
        }
        File regionDir = new File(worldDir, WorldReader.REGION_DIRECTORY);
        if (!regionDir.isDirectory()) {
            regionDir = new File(worldDir, "dimensions/minecraft/overworld/region");
        }
        if (!(regionDir.exists() && regionDir.isDirectory())) {
            throw new NoSuchFieldException("World '" + worldDir.getName() + "' doesn't have a 'region' directory.");
        }
        this.world_ = worldDir;
        this.regions_ = regionDir;
        // There is 32*32 chunks in a region. We probably don't need to cache all of them.
        int chunkCacheSize = cacheSize * 64;
        this.chunkCache_ = new RLUCache<>(chunkCacheSize);
        // Cache as many y levels as it can fit in all the loaded chunks.
        this.yLevelCache_ = new RLUCache<>(chunkCacheSize * Underilla.CHUNK_SIZE * Underilla.CHUNK_SIZE);
        // Cache as many biomes as it can fit in all the loaded chunks.
        this.biomeCache_ = new RLUCacheTriple<>(chunkCacheSize * 4 * 4);
        java.util.Arrays.setAll(chunkLoadLocks, i -> new Object());
    }


    // GETTERS
    public String getWorldName() { return this.world_.getName(); }


    // UTIL
    @Override
    public Optional<Block> blockAt(int x, int y, int z) {
        int chunkX = MCAUtil.blockToChunk(x), chunkZ = MCAUtil.blockToChunk(z);
        return this.readChunk(chunkX, chunkZ).flatMap(c -> c.blockAt(Math.floorMod(x, 16), y, Math.floorMod(z, 16)));
    }
    @Override
    public Optional<Biome> biomeAt(int x, int y, int z) {
        int chunkX = MCAUtil.blockToChunk(x), chunkZ = MCAUtil.blockToChunk(z);
        return this.readChunk(chunkX, chunkZ).flatMap(c -> c.biomeAt(Math.floorMod(x, 16), y, Math.floorMod(z, 16)));
    }
    public Optional<ChunkReader> readChunk(int x, int z) {
        Optional<ChunkReader> cached = this.chunkCache_.get(x, z);
        if (cached != null) {
            return cached;
        }
        synchronized (chunkLoadLocks[(31 * x + z) & (chunkLoadLocks.length - 1)]) {
            cached = this.chunkCache_.get(x, z);
            if (cached != null) {
                return cached;
            }
            try {
                Chunk chunk = RegionChunkReader.read(this.regions_, x, z);
                cached = Optional.ofNullable(chunk).map(this::newChunkReader);
                this.chunkCache_.put(x, z, cached);
                return cached;
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to read reference chunk " + x + ", " + z + " in " + regions_, e);
            }
        }
    }

    public int getLowerBlockOfSurfaceWorldYLevel(int globalX, int globalZ) {
        // Hard limit for NONE & ABSOLUTE merge strategy.
        if (Underilla.getUnderillaConfig().getMergeStrategy() == MergeStrategy.ABSOLUTE
                || Underilla.getUnderillaConfig().getMergeStrategy() == MergeStrategy.NONE) {
            return Underilla.getUnderillaConfig().getInt(IntegerKeys.MAX_HEIGHT_OF_CAVES);
        }
        // Computed limit for SURFACE merge startegy.
        Integer cached = yLevelCache_.get(globalX, globalZ);
        if (cached != null) {
            return cached;
        }
        int r;

        int maxHeightOfCaves = Underilla.getUnderillaConfig().getInt(IntegerKeys.MAX_HEIGHT_OF_CAVES);
        int minimalPossibleY = Underilla.getUnderillaConfig().getInt(IntegerKeys.GENERATION_AREA_MIN_Y);
        if (maxHeightOfCaves <= minimalPossibleY) {
            r = minimalPossibleY;
            yLevelCache_.put(globalX, globalZ, r);
            return r;
        }
        int mergeDepth = Underilla.getUnderillaConfig().getInt(IntegerKeys.MERGE_DEPTH);

        // Optional<Biome> optionalBiome = surfaceReader.biomeAt(globalX, 0, globalZ);
        // Unkown biome or preserved biome.
        if (Underilla.getUnderillaConfig().isBiomeInSet(SetBiomeStringKeys.SURFACE_WORLD_ONLY_ON_THIS_BIOMES,
                getBiomeName(globalX, globalZ))) {
            r = minimalPossibleY;
            yLevelCache_.put(globalX, globalZ, r);
            return r;
        }


        // While is AIR, LEAVES, non solid block, etc, go down.
        ChunkReader column = readChunk(globalX >> 4, globalZ >> 4).orElse(null);
        int lbtr = column == null ? minimalPossibleY
                : Math.max(minimalPossibleY, Math.min(maxHeightOfCaves + mergeDepth, column.airSectionsBottom() - 1));
        while (lbtr > minimalPossibleY && !column.blockAt(Math.floorMod(globalX, 16), lbtr, Math.floorMod(globalZ, 16))
                .orElse(BukkitBlock.AIR).isSolidAndSurfaceBlock()) {
            lbtr--;
        }

        // Cliffs surface fixer to avoid cave blocks being visible on cliffs.
        int extraAdaptativeMaxMergeDepth = Underilla.getUnderillaConfig().getInt(IntegerKeys.ADAPTATIVE_MAX_MERGE_DEPTH) - mergeDepth;
        final int finalDepth;
        if (extraAdaptativeMaxMergeDepth > 0) {
            int minHiddenBlocksDepth = Underilla.getUnderillaConfig().getInt(IntegerKeys.ADAPTATIVE_MIN_HIDDEN_BLOCKS_MERGE_DEPTH);
            int countHowManyBlocksAreExposed = 0;
            // While there is air or grass etc near the block, go down.
            while (extraAdaptativeMaxMergeDepth > 0 && haveNonSolidNeighbour(globalX, lbtr - countHowManyBlocksAreExposed, globalZ)
                    && lbtr > minimalPossibleY) {
                countHowManyBlocksAreExposed++;
                extraAdaptativeMaxMergeDepth--;
            }
            finalDepth = Math.max(mergeDepth, countHowManyBlocksAreExposed + minHiddenBlocksDepth);
        } else {
            finalDepth = mergeDepth;
        }


        r = lbtr - finalDepth;
        yLevelCache_.put(globalX, globalZ, r);
        return r;
    }

    private boolean haveNonSolidNeighbour(int x, int y, int z) {
        // Is there blocks next to that block that are not solid (air, leaves, etc). If no block are found, return false.
        return blockAt(x + 1, y, z).map(b -> !b.isSolid()).orElse(false)
                || blockAt(x - 1, y, z).map(b -> !b.isSolid()).orElse(false)
                || blockAt(x, y, z + 1).map(b -> !b.isSolid()).orElse(false)
                || blockAt(x, y, z - 1).map(b -> !b.isSolid()).orElse(false);
    }

    public String getBiomeName(int globalX, int globalY, int globalZ) {
        // make globalX and globalZ multiple of BIOME_AREA_SIZE ot avoid storing duplicate data.
        globalX = Math.floorDiv(globalX, Underilla.BIOME_AREA_SIZE) * Underilla.BIOME_AREA_SIZE;
        globalY = Math.floorDiv(globalY, Underilla.BIOME_AREA_SIZE) * Underilla.BIOME_AREA_SIZE;
        globalZ = Math.floorDiv(globalZ, Underilla.BIOME_AREA_SIZE) * Underilla.BIOME_AREA_SIZE;

        String cached = biomeCache_.get(globalX, globalY, globalZ);
        if (cached != null) {
            return cached;
        }

        Optional<Biome> optionalBiome = biomeAt(globalX, globalY, globalZ);
        String r = optionalBiome.isEmpty() ? null : optionalBiome.get().getName();
        biomeCache_.put(globalX, globalY, globalZ, r);
        return r;
    }
    public String getBiomeName(int globalX, int globalZ) {
        return getBiomeName(globalX, Underilla.getUnderillaConfig().getInt(IntegerKeys.GENERATION_AREA_MAX_Y), globalZ);
    }


    // ABSTRACT
    protected abstract ChunkReader newChunkReader(Chunk chunk);


    // CLASSES
    public static class RLUCache<T> {

        // FIELDS
        private final Long2ObjectLinkedOpenHashMap<T> map_ = new Long2ObjectLinkedOpenHashMap<>();
        private final int capacity_;


        // CONSTRUCTOR
        RLUCache(int capacity) { this.capacity_ = Math.max(1, capacity); }


        // UTIL
        // Keep reads and FIFO eviction atomic across generation workers.
        T get(int x, int z) {
            long key = ((long) x << 32) | (z & 0xffffffffL);
            synchronized (this) {
                return this.map_.get(key);
            }
        }
        void put(int x, int z, T file) {
            long key = ((long) x << 32) | (z & 0xffffffffL);
            synchronized (this) {
                this.map_.putAndMoveToLast(key, file);
                if (this.map_.size() > this.capacity_) {
                    this.map_.removeFirst();
                }
            }
        }
    }

    public static class RLUCacheTriple<T> {

        // FIELDS
        private final Map<BiomePosition, T> map_ = new LinkedHashMap<>();
        private final int capacity_;


        // CONSTRUCTOR
        RLUCacheTriple(int capacity) { this.capacity_ = Math.max(1, capacity); }


        // UTIL
        // We synchronized the methode to avoid concurrent access to the cache.
        // Concurrent access cause queue_ and map_ to grow without never being reduced.
        // We might win few ms by reducing the part of the code that is synchronized, but I don't think it's worth the potential bugs.
        T get(int x, int y, int z) {
            BiomePosition pair = new BiomePosition(x, y, z);
            synchronized (this) {
                return this.map_.get(pair);
            }
        }
        void put(int x, int y, int z, T file) {
            BiomePosition pair = new BiomePosition(x, y, z);
            synchronized (this) {
                this.map_.remove(pair);
                this.map_.put(pair, file);
                if (this.map_.size() > this.capacity_) {
                    this.map_.remove(this.map_.keySet().iterator().next());
                }
            }
        }
    }

    private record BiomePosition(int x, int y, int z) {
        @Override public int hashCode() {
            int hash = x * 73428767 ^ y * 912931 ^ z * 19349663;
            hash ^= hash >>> 16;
            hash *= 0x7feb352d;
            return hash ^ (hash >>> 15);
        }
    }

}
