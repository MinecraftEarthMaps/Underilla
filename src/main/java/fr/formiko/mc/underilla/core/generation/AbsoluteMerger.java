package fr.formiko.mc.underilla.core.generation;

import fr.formiko.mc.underilla.core.api.Block;
import fr.formiko.mc.underilla.core.api.ChunkData;
import fr.formiko.mc.underilla.core.reader.ChunkReader;
import fr.formiko.mc.underilla.core.reader.WorldReader;
import fr.formiko.mc.underilla.paper.Underilla;
import fr.formiko.mc.underilla.paper.impl.BukkitBlock;
import fr.formiko.mc.underilla.paper.io.UnderillaConfig;
import fr.formiko.mc.underilla.paper.io.UnderillaConfig.IntegerKeys;
import fr.formiko.mc.underilla.paper.io.UnderillaConfig.MapMaterialKeys;
import fr.formiko.mc.underilla.paper.io.UnderillaConfig.SetMaterialKeys;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bukkit.Material;

public class AbsoluteMerger implements Merger {

    // FIELDS
    private final WorldReader worldSurfaceReader;

    // CONSTRUCTORS
    AbsoluteMerger(WorldReader worldSurfaceReader) { this.worldSurfaceReader = worldSurfaceReader; }


    // IMPLEMENTATIONS
    @Override
    public void mergeLand(@Nonnull ChunkReader surfaceReader, @Nonnull ChunkData chunkData, @Nullable ChunkReader cavesReader) {
        long startTime = System.currentTimeMillis();
        UnderillaConfig config = Underilla.getUnderillaConfig();
        int minY = Math.max(chunkData.getMinHeight(), config.getInt(IntegerKeys.GENERATION_AREA_MIN_Y));
        int airColumn = Math.max(minY, Math.min(surfaceReader.airSectionsBottom(), chunkData.getMaxHeight()));
        chunkData.setRegion(0, airColumn, 0, Underilla.CHUNK_SIZE, chunkData.getMaxHeight(), Underilla.CHUNK_SIZE, BukkitBlock.AIR);

        Set<Material> kept = config.getSetMaterial(SetMaterialKeys.BLOCK_TO_KEEP_FROM_SURFACE_WORLD_IN_CAVES);
        Map<Material, Material> replacements = config.getMapMaterial(MapMaterialKeys.SURFACE_WORLD_BLOCK_TO_REPLACE);
        Map<Material, Block> replacementBlocks = new EnumMap<>(Material.class);
        // With no reference ores or imported caves, everything below the seam is already correct.
        boolean surfaceOnly = kept.isEmpty() && cavesReader == null;
        for (int x = 0; x < Underilla.CHUNK_SIZE; x++) {
            for (int z = 0; z < Underilla.CHUNK_SIZE; z++) {
                int seam = worldSurfaceReader.getLowerBlockOfSurfaceWorldYLevel(surfaceReader.getGlobalX(x), surfaceReader.getGlobalZ(z));
                int bottom = surfaceOnly ? Math.max(minY, seam + 1) : minY;
                for (int y = bottom; y < airColumn; y++) {
                    Block custom = surfaceReader.blockAt(x, y, z).orElse(BukkitBlock.AIR);
                    if (!replacements.isEmpty()) {
                        Material replacement = replacements.get(materialOf(custom));
                        if (replacement != null) {
                            custom = replacementBlocks.computeIfAbsent(replacement, m -> new BukkitBlock(m.createBlockData()));
                        }
                    }
                    if (y > seam) {
                        chunkData.setBlock(x, y, z, custom);
                    } else if (!kept.isEmpty() && kept.contains(materialOf(custom))) {
                        Block vanilla = cavesReader == null ? chunkData.getBlock(x, y, z)
                                : cavesReader.blockAt(x, y, z).orElse(BukkitBlock.AIR);
                        if (vanilla == null || vanilla.isSolid()) {
                            chunkData.setBlock(x, y, z, custom);
                        } else if (cavesReader != null) {
                            chunkData.setBlock(x, y, z, vanilla);
                        }
                    } else if (cavesReader != null) {
                        chunkData.setBlock(x, y, z, cavesReader.blockAt(x, y, z).orElse(BukkitBlock.AIR));
                    }
                }
            }
        }
        // Record once per chunk; per-block clocks and shared map writes dominated this loop.
        Generator.addTime("Merge land", startTime);
    }

    private static Material materialOf(Block block) {
        return block instanceof BukkitBlock bukkitBlock ? bukkitBlock.getMaterial() : Material.matchMaterial(block.getName());
    }
}
