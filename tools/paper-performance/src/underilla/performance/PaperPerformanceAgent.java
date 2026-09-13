package underilla.performance;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.Properties;

/** Optional startup adapter; unmatched server classes retain their original behavior. */
public final class PaperPerformanceAgent {
    public static void premain(String mode, Instrumentation instrumentation) throws Exception {
        var hashes = new Properties();
        try (var input = PaperPerformanceAgent.class.getResourceAsStream("/patches/original.properties")) {
            hashes.load(input);
        }
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> redefined,
                    ProtectionDomain domain, byte[] bytes) {
                String expected = hashes.getProperty(name);
                if (expected == null || ("climate".equals(mode) && !name.endsWith("CustomWorldChunkManager"))
                        || ("surface".equals(mode) && !name.endsWith("SequenceRule"))) return null;
                try {
                    String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                    if (!expected.equals(actual)) {
                        System.err.println("[UnderillaPerformance] Unsupported class version; optimization skipped: " + name);
                        return null;
                    }
                    try (var input = PaperPerformanceAgent.class.getResourceAsStream("/patches/" + name + ".class")) {
                        byte[] replacement = input.readAllBytes();
                        System.out.println("[UnderillaPerformance] Applied verified optimization: " + name);
                        return replacement;
                    }
                } catch (Exception error) {
                    System.err.println("[UnderillaPerformance] Optimization skipped: " + name + ": " + error);
                    return null;
                }
            }
        });
    }
}
