package studios.milkdromeda.octo.rebuild;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import studios.milkdromeda.octo.compat.mapping.MappingProvider;
import studios.milkdromeda.octo.mod.Side;

/**
 * A game class this version dropped, added back from the release a mod came
 * from.
 *
 * <p>The old release here is a two-class stand-in for 1.21.1: obfuscated
 * {@code x} is {@code ResourceLocation} (today's {@code Identifier}), and
 * {@code x$a} is its {@code Serializer}, which no later release has. The tables
 * and the old jar are written into the cache the way a first launch would have
 * left them, so nothing goes near the network.
 */
class RestoreTest {
    private static final String SERIALIZER = "net/minecraft/resources/ResourceLocation$Serializer";

    @TempDir
    Path game;

    private Path cache;

    @BeforeEach
    void seed() throws Exception {
        cache = game.resolve(".octo").resolve("cache");
        Path raw = cache.resolve("mappings").resolve("raw");
        Files.createDirectories(raw);

        intermediaryJar(raw.resolve("intermediary-1.21.11-v2.jar"), """
                tiny\t2\t0\tofficial\tintermediary
                c\td\tnet/minecraft/class_2960
                \tm\t()Ljava/lang/String;\te\tmethod_12832
                """);
        Files.writeString(raw.resolve("client-1.21.11.txt"), """
                net.minecraft.resources.Identifier -> d:
                    java.lang.String getPath() -> e
                """);

        intermediaryJar(raw.resolve("intermediary-1.21.1-v2.jar"), """
                tiny\t2\t0\tofficial\tintermediary
                c\tx\tnet/minecraft/class_2960
                \tm\t()Ljava/lang/String;\ty\tmethod_12832
                c\tx$a\tnet/minecraft/class_2960$class_2961
                \tm\t(Lx;)Ljava/lang/String;\ta\tmethod_99
                c\tz\tnet/minecraft/client/class_310
                """);
        Files.writeString(raw.resolve("client-1.21.1.txt"), """
                net.minecraft.resources.ResourceLocation -> x:
                    java.lang.String getPath() -> y
                net.minecraft.resources.ResourceLocation$Serializer -> x$a:
                    java.lang.String describe(net.minecraft.resources.ResourceLocation) -> a
                net.minecraft.client.Minecraft -> z:
                """);

        Path oldGame = cache.resolve("game");
        Files.createDirectories(oldGame);

        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(oldGame.resolve("client-1.21.1.jar")))) {
            out.putNextEntry(new ZipEntry("x$a.class"));
            out.write(obfuscatedSerializer());
            out.closeEntry();
            out.putNextEntry(new ZipEntry("z.class"));
            out.write(emptyClass("z"));
            out.closeEntry();
        }
    }

    private static void intermediaryJar(Path jar, String tiny) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("mappings/mappings.tiny"));
            out.write(tiny.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    /** {@code x$a}: a Gson-style adapter that describes a ResourceLocation by its path. */
    private static byte[] obfuscatedSerializer() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "x$a", null, "java/lang/Object",
                new String[] { "java/util/function/Function" });
        MethodVisitor describe = writer.visitMethod(Opcodes.ACC_PUBLIC, "a", "(Lx;)Ljava/lang/String;", null, null);
        describe.visitCode();
        describe.visitVarInsn(Opcodes.ALOAD, 1);
        describe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "x", "y", "()Ljava/lang/String;", false);
        describe.visitInsn(Opcodes.ARETURN);
        describe.visitMaxs(0, 0);
        describe.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private ClassRestorer restorer(Side side) {
        return new ClassRestorer(new MappingProvider(cache.resolve("mappings"), true), List.of("1.21.1"), side,
                cache.resolve("restored"));
    }

    /** What a class file declares and calls, as plain strings. */
    private static List<String> shape(byte[] bytes) {
        List<String> out = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                    String[] interfaces) {
                out.add("class " + name);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                out.add("method " + name + descriptor);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called, String calledDescriptor,
                            boolean isInterface) {
                        out.add("call " + owner + "." + called + calledDescriptor);
                    }
                };
            }
        }, 0);
        return out;
    }

    @Test
    @DisplayName("a class gone from this version comes back from the old release, in today's names")
    void restoresInTodaysNames() {
        ClassRestorer restorer = restorer(Side.SERVER);
        byte[] restored = restorer.restore(SERIALIZER);

        assertNotNull(restored);
        List<String> shape = shape(restored);
        assertTrue(shape.contains("class " + SERIALIZER), shape.toString());
        assertTrue(shape.contains("method describe(Lnet/minecraft/resources/Identifier;)Ljava/lang/String;"),
                shape.toString());
        assertTrue(shape.contains("call net/minecraft/resources/Identifier.getPath()Ljava/lang/String;"),
                shape.toString());
        assertTrue(restorer.isNamedByRestored("net/minecraft/resources/Identifier"));
        assertEquals(1, restorer.restored().size());
    }

    @Test
    @DisplayName("an inner class that moved with its outer one is found under that spelling too")
    void innerClassUnderItsOutersNewName() {
        String moved = "net/minecraft/resources/Identifier$Serializer";
        byte[] restored = restorer(Side.SERVER).restore(moved);

        assertNotNull(restored, "a mod rebuilt from Mojang's 1.21 names asks for it as Identifier$Serializer");
        assertTrue(shape(restored).contains("class " + moved), "the class must be defined under the name asked for");
    }

    @Test
    @DisplayName("a class added back once is reused from the cache without the old jar")
    void restoredClassesAreCached() throws Exception {
        byte[] first = restorer(Side.SERVER).restore(SERIALIZER);
        Files.delete(cache.resolve("game").resolve("client-1.21.1.jar"));

        assertArrayEquals(first, restorer(Side.SERVER).restore(SERIALIZER));
    }

    @Test
    @DisplayName("a client class is not added back to a dedicated server")
    void clientClassesStayOffServers() {
        assertNull(restorer(Side.SERVER).restore("net/minecraft/client/Minecraft"));
        assertNotNull(restorer(Side.CLIENT).restore("net/minecraft/client/Minecraft"));
    }

    @Test
    @DisplayName("a class no release had is left to the stand-ins")
    void unknownClassesAreLeftAlone() {
        assertNull(restorer(Side.CLIENT).restore("net/minecraft/NeverExisted"));
        assertNull(restorer(Side.CLIENT).restore("com/example/NotTheGame"));
    }
}
