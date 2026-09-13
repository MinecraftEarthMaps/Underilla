package fr.formiko.mc.underilla.paper.listener;

import fr.formiko.mc.underilla.core.reader.WorldReader;
import fr.formiko.mc.underilla.paper.Underilla;
import fr.formiko.mc.underilla.paper.impl.BukkitBlock;
import fr.formiko.mc.underilla.paper.io.UnderillaConfig.MapMaterialKeys;
import fr.formiko.mc.underilla.paper.io.UnderillaConfig.StringKeys;
import java.util.EnumMap;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/** Restores the protected surface after neighbouring decoration and lighting have completed. */
public final class SurfaceRestoreListener implements Listener {
    private final WorldReader reference;

    public SurfaceRestoreListener(WorldReader reference) { this.reference = reference; }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk() || !event.getWorld().getName().equals(
                Underilla.getUnderillaConfig().getString(StringKeys.FINAL_WORLD_NAME))) {
            return;
        }
        var chunk = event.getChunk();
        var source = reference.readChunk(chunk.getX(), chunk.getZ()).orElse(null);
        if (source == null) {
            return;
        }
        var replacements = Underilla.getUnderillaConfig().getMapMaterial(MapMaterialKeys.SURFACE_WORLD_BLOCK_TO_REPLACE);
        var replacementData = new EnumMap<Material, BlockData>(Material.class);
        int top = Math.min(source.airSectionsBottom(), event.getWorld().getMaxHeight());
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int bottom = Math.max(event.getWorld().getMinHeight(),
                        reference.getLowerBlockOfSurfaceWorldYLevel(source.getGlobalX(x), source.getGlobalZ(z)) + 1);
                for (int y = bottom; y < top; y++) {
                    BukkitBlock original = (BukkitBlock) source.blockAt(x, y, z).orElse(BukkitBlock.AIR);
                    Material replacement = replacements.get(original.getMaterial());
                    BlockData expected = replacement == null ? original.getBlockData()
                            : replacementData.computeIfAbsent(replacement, Material::createBlockData);
                    var target = chunk.getBlock(x, y, z);
                    restoreBlock(target, expected);
                }
            }
        }
        // Vanilla decoration can also add blocks above the tallest reference section.
        var snapshot = chunk.getChunkSnapshot(false, false, false);
        for (int y = top; y < event.getWorld().getMaxHeight(); y += 16) {
            if (snapshot.isSectionEmpty((y - event.getWorld().getMinHeight()) >> 4)) {
                continue;
            }
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                for (int dy = y; dy < Math.min(y + 16, event.getWorld().getMaxHeight()); dy++) {
                    var target = chunk.getBlock(x, dy, z);
                    if (!target.getType().isAir()) restoreBlock(target, BukkitBlock.AIR.getBlockData());
                }
            }
        }
    }

    private static void restoreBlock(Block target, BlockData expected) {
        if (expected.equals(target.getBlockData())) return;
        if (target.getType() != expected.getMaterial()) {
            CraftBlock craftBlock = (CraftBlock) target;
            if (craftBlock.getBlockState().hasBlockEntity()) {
                // A newly generated chest/vault may still have pending NBT. Clear it before
                // setBlockData changes its type: Paper otherwise promotes it against the new
                // state (e.g. water). Same-type updates retain their block entity and inventory.
                LevelChunk chunk = (LevelChunk) ((CraftChunk) target.getChunk()).getHandle(ChunkStatus.FULL);
                chunk.removeBlockEntity(craftBlock.getPosition());
            }
        }
        target.setBlockData(expected, false);
    }
}
