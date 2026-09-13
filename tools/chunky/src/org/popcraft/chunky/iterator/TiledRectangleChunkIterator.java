package org.popcraft.chunky.iterator;

import java.util.NoSuchElementException;
import org.popcraft.chunky.Selection;
import org.popcraft.chunky.util.ChunkCoordinate;

/** A versioned, resumable traversal: region X, region Z, chunk X, chunk Z. */
public final class TiledRectangleChunkIterator implements ChunkIterator {
    public static final String PATTERN = "region-rectangle-v1";
    private final int minX, minZ, maxX, maxZ;
    private final long total;
    private int tileX, tileZ, tileMinX, tileMaxX, tileMinZ, tileMaxZ, x, z;
    private long position;

    public TiledRectangleChunkIterator(Selection selection, long count) {
        this(selection.centerChunkX() - selection.radiusChunksX(),
                selection.centerChunkZ() - selection.radiusChunksZ(),
                selection.centerChunkX() + selection.radiusChunksX(),
                selection.centerChunkZ() + selection.radiusChunksZ(), count);
    }

    TiledRectangleChunkIterator(int minX, int minZ, int maxX, int maxZ, long count) {
        if (maxX < minX || maxZ < minZ) throw new IllegalArgumentException("Invalid rectangle");
        this.minX = minX; this.minZ = minZ; this.maxX = maxX; this.maxZ = maxZ;
        total = Math.multiplyExact((long) maxX - minX + 1, (long) maxZ - minZ + 1);
        if (count < 0 || count > total) throw new IllegalArgumentException("Invalid checkpoint cursor");
        position = count;
        tileX = minX >> 5; tileZ = minZ >> 5;
        if (count == total) return;
        // Skip whole tiles so resuming uses constant memory.
        while (true) {
            setTileBounds();
            long size = (long) (tileMaxX - tileMinX + 1) * (tileMaxZ - tileMinZ + 1);
            if (count < size) break;
            count -= size;
            advanceTile();
        }
        int depth = tileMaxZ - tileMinZ + 1;
        x = tileMinX + (int) (count / depth);
        z = tileMinZ + (int) (count % depth);
    }

    private void setTileBounds() {
        tileMinX = Math.max(minX, tileX << 5); tileMaxX = Math.min(maxX, (tileX << 5) + 31);
        tileMinZ = Math.max(minZ, tileZ << 5); tileMaxZ = Math.min(maxZ, (tileZ << 5) + 31);
    }

    private void advanceTile() {
        if (++tileZ > (maxZ >> 5)) { tileZ = minZ >> 5; tileX++; }
    }

    @Override public boolean hasNext() { return position < total; }
    @Override public long total() { return total; }
    @Override public String name() { return PATTERN; }

    @Override public ChunkCoordinate next() {
        if (!hasNext()) throw new NoSuchElementException();
        var result = new ChunkCoordinate(x, z);
        if (++position == total) return result;
        if (++z > tileMaxZ) {
            z = tileMinZ;
            if (++x > tileMaxX) {
                advanceTile(); setTileBounds(); x = tileMinX; z = tileMinZ;
            }
        }
        return result;
    }
}
