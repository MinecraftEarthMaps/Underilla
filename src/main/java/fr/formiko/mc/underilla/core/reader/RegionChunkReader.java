package fr.formiko.mc.underilla.core.reader;

import com.jkantrell.mca.Chunk;
import com.jkantrell.nbt.io.NBTDeserializer;
import com.jkantrell.nbt.tag.CompoundTag;
import java.io.*;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/** Reads one Anvil chunk without decompressing the other 1,023 chunks in its region. */
final class RegionChunkReader {
    private RegionChunkReader() {}

    static Chunk read(File directory, int x, int z) throws IOException {
        File region = new File(directory, "r." + (x >> 5) + "." + (z >> 5) + ".mca");
        if (!region.isFile()) {
            return null;
        }
        byte[] payload;
        int compression;
        try (RandomAccessFile file = new RandomAccessFile(region, "r")) {
            file.seek(4L * (Math.floorMod(x, 32) + Math.floorMod(z, 32) * 32));
            int location = file.readInt();
            if (location == 0) {
                return null;
            }
            long offset = (location >>> 8) * 4096L;
            int sectors = location & 255;
            if (offset < 8192 || sectors == 0 || offset + 5 > file.length()) {
                throw new IOException("Invalid location for chunk " + x + ", " + z);
            }
            file.seek(offset);
            int length = file.readInt();
            compression = file.readUnsignedByte();
            if ((compression & 128) != 0) {
                if (length != 1) {
                    throw new IOException("Invalid external chunk length: " + length);
                }
                payload = Files.readAllBytes(new File(directory, "c." + x + "." + z + ".mcc").toPath());
                compression &= 127;
            } else {
                if (length < 1 || length > sectors * 4096 - 4 || offset + 4 + length > file.length()) {
                    throw new IOException("Invalid chunk length: " + length);
                }
                payload = new byte[length - 1];
                file.readFully(payload);
            }
        }
        InputStream bytes = new ByteArrayInputStream(payload);
        try (InputStream decoded = switch (compression) {
            case 1 -> new GZIPInputStream(bytes);
            case 2 -> new InflaterInputStream(bytes);
            case 0, 3 -> bytes;
            case 4 -> new net.jpountz.lz4.LZ4BlockInputStream(bytes);
            default -> throw new IOException("Unsupported Anvil compression type: " + compression);
        }) {
            var tag = new NBTDeserializer(false).fromStream(new BufferedInputStream(decoded));
            if (tag == null || !(tag.getTag() instanceof CompoundTag data)) {
                throw new IOException("Invalid NBT for chunk " + x + ", " + z);
            }
            Chunk chunk = new Chunk(data);
            if (chunk.getX() != x || chunk.getZ() != z) {
                throw new IOException("Chunk coordinates do not match region location: " + x + ", " + z);
            }
            return chunk;
        }
    }
}
