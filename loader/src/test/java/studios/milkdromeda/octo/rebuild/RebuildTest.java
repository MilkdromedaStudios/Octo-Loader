package studios.milkdromeda.octo.rebuild;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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

import studios.milkdromeda.octo.compat.Era;
import studios.milkdromeda.octo.compat.Era.MappingNamespace;
import studios.milkdromeda.octo.compat.TimeCapsule;
import studios.milkdromeda.octo.compat.mapping.MappingProvider;
import studios.milkdromeda.octo.compat.mapping.MappingRegistry;
import studios.milkdromeda.octo.compat.mapping.MappingSet;
import studios.milkdromeda.octo.launch.LaunchContext;
import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModDependency;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.testkit.JarBuilder;

/**
 * Old mods are unpacked, rewritten into today's names, packed again and cached.
 *
 * <p>The tables here are tiny stand-ins for Mojang's and Fabric's published
 * ones, written straight into the cache the way a first launch would have left
 * them, so nothing goes near the network.
 */
class RebuildTest {
    @TempDir
    Path game;

    private Path mappings;

    @BeforeEach
    void seedTables() throws Exception {
        MappingRegistry.reset();
        mappings = game.resolve(".octo").resolve("cache").resolve("mappings");
        Path raw = mappings.resolve("raw");
        Files.createDirectories(raw);

        // 1.21.11: obfuscated "a" is Thing, intermediary class_1.
        intermediaryJar(raw.resolve("intermediary-1.21.11-v2.jar"), """
                tiny\t2\t0\tofficial\tintermediary
                c\ta\tnet/minecraft/class_1
                \tm\t()V\tb\tmethod_2
                \tf\tI\tc\tfield_3
                c\td\tnet/minecraft/class_2960
                \tm\t()Ljava/lang/String;\te\tmethod_12832
                """);
        Files.writeString(raw.resolve("client-1.21.11.txt"), """
                net.minecraft.world.Thing -> a:
                    int count -> c
                    void tick() -> b
                net.minecraft.resources.Identifier -> d:
                    java.lang.String getPath() -> e
                """);

        // 1.21.1: the same identifier class was called ResourceLocation, and was "x".
        intermediaryJar(raw.resolve("intermediary-1.21.1-v2.jar"), """
                tiny\t2\t0\tofficial\tintermediary
                c\tx\tnet/minecraft/class_2960
                \tm\t()Ljava/lang/String;\ty\tmethod_12832
                """);
        Files.writeString(raw.resolve("client-1.21.1.txt"), """
                net.minecraft.resources.ResourceLocation -> x:
                    java.lang.String getPath() -> y
                """);
    }

    private static void intermediaryJar(Path jar, String tiny) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("mappings/mappings.tiny"));
            out.write(tiny.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
    }

    private MappingProvider provider() {
        return new MappingProvider(mappings, true);
    }

    @Test
    @DisplayName("Fabric intermediary is taken to today's names through Mojang's published maps")
    void intermediaryToToday() {
        MappingSet set = provider().toCurrent(MappingNamespace.INTERMEDIARY, null).orElseThrow();

        assertEquals("net/minecraft/world/Thing", set.mapClass("net/minecraft/class_1"));
        assertEquals("tick", set.mapMethod("com/example/AnySubclass", "method_2", "()V"));
        assertEquals("count", set.mapField("net/minecraft/class_1", "field_3", "I"));
        assertEquals("Lnet/minecraft/world/Thing;tick()V",
                set.mapString("Lnet/minecraft/class_1;method_2()V"));
    }

    @Test
    @DisplayName("an old release's Mojang names reach today's through intermediary, renames included")
    void oldMojangNamesToToday() {
        MappingSet set = provider().toCurrent(MappingNamespace.MOJANG, "1.21.1").orElseThrow();

        assertEquals("net/minecraft/resources/Identifier", set.mapClass("net/minecraft/resources/ResourceLocation"));
    }

    @Test
    @DisplayName("a composed table is cached, and the cache answers the same")
    void composedTablesAreCached() throws Exception {
        provider().toCurrent(MappingNamespace.INTERMEDIARY, null).orElseThrow();

        try (var files = Files.list(mappings)) {
            assertTrue(files.anyMatch(file -> file.getFileName().toString().endsWith(".octomap")));
        }

        MappingSet reloaded = provider().toCurrent(MappingNamespace.INTERMEDIARY, null).orElseThrow();
        assertEquals("net/minecraft/world/Thing", reloaded.mapClass("net/minecraft/class_1"));
    }

    /** A Fabric mod for 1.21.1: calls class_1.method_2, reads field_3, and has a refmap. */
    private Path oldFabricMod() throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "com/example/OldMod", null, "net/minecraft/class_1", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()I", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/example/OldMod", "method_2", "()V", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/class_1", "field_3", "I");
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();

        return new JarBuilder()
                .file("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"oldmod\",\"version\":\"1.0.0\"}")
                .file("com/example/OldMod.class", writer.toByteArray())
                .file("oldmod.refmap.json",
                        "{\"mappings\":{\"com/example/Mixin\":{\"tick\":\"Lnet/minecraft/class_1;method_2()V\"}}}")
                .file("oldmod.accesswidener", "accessWidener\tv2\tintermediary\naccessible\tfield\t"
                        + "net/minecraft/class_1\tfield_3\tI\n")
                .file("META-INF/OLDMOD.SF", "signature over the original classes")
                .writeTo(game.resolve("mods").resolve("oldmod.jar"));
    }

    private LoadedMod loaded(Path jar) {
        ModMetadata metadata = ModMetadata.builder("oldmod", ModFormat.FABRIC).version("1.0.0")
                .dependency(ModDependency.required("minecraft", "~1.21.1")).build();
        ModCandidate candidate = new ModCandidate(jar, metadata, null);
        candidate.era(Era.CURRENT);
        candidate.namespace(MappingNamespace.INTERMEDIARY);
        return new LoadedMod(candidate);
    }

    private ModRebuilder rebuilder() {
        LaunchContext context = LaunchContext.builder(game).minecraftVersion("26.3").build();
        return new ModRebuilder(context, new TimeCapsule(context), provider());
    }

    @Test
    @DisplayName("an intermediary mod is unpacked, rewritten into today's names and packed again")
    void rebuildsAnOldMod() throws Exception {
        Path source = oldFabricMod();
        ModRebuilder.Result result = rebuilder().prepare(loaded(source));

        assertTrue(result.rebuilt());
        assertNotEquals(source, result.jar());
        assertTrue(result.jar().startsWith(game.resolve(".octo").resolve("cache").resolve("rebuilt")));

        try (ZipFile zip = new ZipFile(result.jar().toFile())) {
            List<String> references = references(zip.getInputStream(zip.getEntry("com/example/OldMod.class"))
                    .readAllBytes());

            assertTrue(references.contains("super net/minecraft/world/Thing"), references.toString());
            assertTrue(references.contains("call tick()V"), references.toString());
            assertTrue(references.contains("field net/minecraft/world/Thing.count"), references.toString());
            assertFalse(references.stream().anyMatch(reference -> reference.contains("class_")), references.toString());

            String refmap = new String(zip.getInputStream(zip.getEntry("oldmod.refmap.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(refmap.contains("Lnet/minecraft/world/Thing;tick()V"), refmap);

            String widener = new String(zip.getInputStream(zip.getEntry("oldmod.accesswidener")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(widener.startsWith("accessWidener\tv2\tnamed"), widener);
            assertTrue(widener.contains("net/minecraft/world/Thing\tcount\tI"), widener);

            assertEquals(null, zip.getEntry("META-INF/OLDMOD.SF"), "a signature over rewritten classes is dropped");
            assertTrue(zip.getEntry("META-INF/octo-rebuilt.properties") != null);
        }
    }

    @Test
    @DisplayName("the second launch loads the rebuilt jar from the cache instead of rebuilding")
    void secondLaunchUsesTheCache() throws Exception {
        Path source = oldFabricMod();
        ModRebuilder.Result first = rebuilder().prepare(loaded(source));
        ModRebuilder.Result second = rebuilder().prepare(loaded(source));

        assertFalse(first.fromCache());
        assertTrue(second.fromCache());
        assertEquals(first.jar(), second.jar());
    }

    @Test
    @DisplayName("a mod already speaking today's names is left exactly as it is")
    void currentModIsUntouched() throws Exception {
        Path source = oldFabricMod();
        LoadedMod mod = loaded(source);
        mod.candidate().era(Era.UNOBFUSCATED);
        mod.candidate().namespace(MappingNamespace.MOJANG);

        assertFalse(rebuilder().prepare(mod).rebuilt());
    }

    private static List<String> references(byte[] bytes) {
        List<String> out = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName,
                    String[] interfaces) {
                out.add("super " + superName);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String member, String desc, boolean itf) {
                        out.add("call " + member + desc);
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String member, String desc) {
                        out.add("field " + owner + "." + member);
                    }
                };
            }
        }, 0);
        return out;
    }
}
