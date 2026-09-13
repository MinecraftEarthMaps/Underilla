import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.objectweb.asm.Opcodes.*;

/** Builds two narrow, version-checked bytecode adaptations without changing the Paper jar. */
public final class TransformPaper {
    private static final String MANAGER = "org/bukkit/craftbukkit/generator/CustomWorldChunkManager";
    private static final String SEQUENCE = "net/minecraft/world/level/levelgen/SurfaceRules$SequenceRule";
    private static final String RULE = "net/minecraft/world/level/levelgen/SurfaceRules$SurfaceRule";
    private static final String APPLY = "(III)Lnet/minecraft/world/level/block/state/BlockState;";
    private static final Map<String,String> ORIGINALS = Map.of(
            MANAGER,"6b91fecf5d3fc776cd622ed1df0f26b03cae447d004a522f6787939f4dd149db",
            SEQUENCE,"2ce927439c7d5e073d9a8af664406b304d43df3602778515ca212eca3f761007");

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[1]);
        try (var jar = new JarFile(args[0])) {
            for (var entry : ORIGINALS.entrySet()) {
                byte[] original = jar.getInputStream(jar.getJarEntry(entry.getKey()+".class")).readAllBytes();
                if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)).equals(entry.getValue()))
                    throw new IllegalArgumentException("Unsupported Paper class: " + entry.getKey());
                var node = new ClassNode(); new ClassReader(original).accept(node,0);
                if (entry.getKey().equals(MANAGER)) climate(node); else surface(node);
                var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                Path target = output.resolve("patches/"+entry.getKey()+".class");
                Files.createDirectories(target.getParent()); Files.write(target,writer.toByteArray());
            }
        }
        var properties = new Properties(); properties.putAll(ORIGINALS);
        try(var stream=Files.newOutputStream(output.resolve("patches/original.properties"))) {
            properties.store(stream,"Paper 26.2 build 123 original class hashes");
        }
    }

    private static void climate(ClassNode node) {
        var method = node.methods.stream().filter(m->m.name.equals("getNoiseBiome")).findFirst().orElseThrow();
        AbstractInsnNode convert = null;
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("org/bukkit/craftbukkit/block/CraftBiome")
                    && call.name.equals("bukkitToMinecraftHolder")) {convert=instruction;break;}
        }
        if (convert == null) throw new IllegalStateException("Biome conversion changed");
        var conversion = new LabelNode(); method.instructions.insertBefore(convert,conversion);
        var prefix = new MethodNode(); var original = new Label();
        prefix.visitVarInsn(ALOAD,0);
        prefix.visitFieldInsn(GETFIELD,MANAGER,"biomeProvider","Lorg/bukkit/generator/BiomeProvider;");
        prefix.visitMethodInsn(INVOKEVIRTUAL,"java/lang/Object","getClass","()Ljava/lang/Class;",false);
        prefix.visitMethodInsn(INVOKEVIRTUAL,"java/lang/Class","getName","()Ljava/lang/String;",false);
        prefix.visitLdcInsn("fr.formiko.mc.underilla.paper.generation.UnderillaChunkGenerator$BiomeProviderFromFile");
        prefix.visitMethodInsn(INVOKEVIRTUAL,"java/lang/String","equals","(Ljava/lang/Object;)Z",false);
        prefix.visitJumpInsn(IFEQ,original);
        prefix.visitVarInsn(ALOAD,0);
        prefix.visitFieldInsn(GETFIELD,MANAGER,"biomeProvider","Lorg/bukkit/generator/BiomeProvider;");
        prefix.visitVarInsn(ALOAD,0);
        prefix.visitFieldInsn(GETFIELD,MANAGER,"worldInfo","Lorg/bukkit/generator/WorldInfo;");
        for(int local=1;local<=3;local++) {
            prefix.visitVarInsn(ILOAD,local);
            prefix.visitMethodInsn(INVOKESTATIC,"net/minecraft/core/QuartPos","toBlock","(I)I",false);
        }
        // The four-argument Underilla provider samples vanilla climate on an actual
        // cache miss. Calling it directly avoids Paper's unconditional sample on hits.
        prefix.visitMethodInsn(INVOKEVIRTUAL,"org/bukkit/generator/BiomeProvider","getBiome",
                "(Lorg/bukkit/generator/WorldInfo;III)Lorg/bukkit/block/Biome;",false);
        prefix.instructions.add(new JumpInsnNode(GOTO,conversion));
        prefix.visitLabel(original);
        method.instructions.insert(prefix.instructions);
    }

    private static void surface(ClassNode node) {
        var method = node.methods.stream().filter(m->m.name.equals("tryApply") && m.desc.equals(APPLY)).findFirst().orElseThrow();
        var prefix = new MethodNode(); var original = new Label(); var loop = new Label();
        var next = new Label(); var empty = new Label();
        prefix.visitVarInsn(ALOAD,0); prefix.visitFieldInsn(GETFIELD,SEQUENCE,"rules","Ljava/util/List;");
        prefix.visitTypeInsn(INSTANCEOF,"java/util/RandomAccess"); prefix.visitJumpInsn(IFEQ,original);
        prefix.visitVarInsn(ALOAD,0); prefix.visitFieldInsn(GETFIELD,SEQUENCE,"rules","Ljava/util/List;");
        prefix.visitVarInsn(ASTORE,4);
        prefix.visitVarInsn(ALOAD,4); prefix.visitMethodInsn(INVOKEINTERFACE,"java/util/List","size","()I",true);
        prefix.visitVarInsn(ISTORE,5); prefix.visitInsn(ICONST_0); prefix.visitVarInsn(ISTORE,6);
        prefix.visitLabel(loop);
        prefix.visitVarInsn(ILOAD,6); prefix.visitVarInsn(ILOAD,5); prefix.visitJumpInsn(IF_ICMPGE,empty);
        prefix.visitVarInsn(ALOAD,4); prefix.visitVarInsn(ILOAD,6);
        prefix.visitMethodInsn(INVOKEINTERFACE,"java/util/List","get","(I)Ljava/lang/Object;",true);
        prefix.visitTypeInsn(CHECKCAST,RULE);
        for(int local=1;local<=3;local++)prefix.visitVarInsn(ILOAD,local);
        prefix.visitMethodInsn(INVOKEINTERFACE,RULE,"tryApply",APPLY,true);
        prefix.visitVarInsn(ASTORE,7); prefix.visitVarInsn(ALOAD,7); prefix.visitJumpInsn(IFNULL,next);
        prefix.visitVarInsn(ALOAD,7); prefix.visitInsn(ARETURN);
        prefix.visitLabel(next); prefix.visitIincInsn(6,1); prefix.visitJumpInsn(GOTO,loop);
        prefix.visitLabel(empty); prefix.visitInsn(ACONST_NULL); prefix.visitInsn(ARETURN);
        // Custom linked lists retain the original iterator path.
        prefix.visitLabel(original);
        method.instructions.insert(prefix.instructions);
    }
}
