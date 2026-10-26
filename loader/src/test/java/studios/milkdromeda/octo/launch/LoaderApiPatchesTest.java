package studios.milkdromeda.octo.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import studios.milkdromeda.octo.access.InjectedInterface;
import studios.milkdromeda.octo.testkit.JarBuilder;
import studios.milkdromeda.octo.transform.TransformContext;
import studios.milkdromeda.octo.transform.Transformer;

/**
 * What the NeoForge API jar says about the game it expects, applied to the
 * game Octo has.
 */
class LoaderApiPatchesTest {
    private static final String HOLDER = "net/neoforged/neoforge/attachment/AttachmentHolder";
    private static final String ENTITY = "net/minecraft/world/entity/Entity";

    @TempDir
    Path dir;

    private static byte[] emptyClass(String name, String superName, int access) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, access, name, null, superName, null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    /** The unpatched game's Entity: extends Object, and makes an Object of its own in its constructor. */
    private static byte[] vanillaEntity() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, ENTITY, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "lock", "Ljava/lang/Object;", null, null).visitEnd();
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitTypeInsn(Opcodes.NEW, "java/lang/Object");
        init.visitInsn(Opcodes.DUP);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitFieldInsn(Opcodes.PUTFIELD, ENTITY, "lock", "Ljava/lang/Object;");
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private Path apiJar() throws Exception {
        return new JarBuilder()
                .file("META-INF/injected-interfaces.json", """
                        {
                          "net/minecraft/world/item/ItemStack": [
                            "net/neoforged/neoforge/common/extensions/IItemStackExtension"
                          ],
                          "net/minecraft/core/Holder": [
                            "net/neoforged/neoforge/common/extensions/IHolderExtension<T>"
                          ]
                        }
                        """)
                .file("META-INF/neoforge.mods.toml", """
                        modLoader="javafml"
                        [[mods]]
                            modId="neoforge"
                        [[mixins]]
                            config = "neoforge.mixins.json"
                        [[accessTransformers]]
                            file="META-INF/accesstransformer.cfg"
                        """)
                .file("META-INF/accesstransformer.cfg", "public net.minecraft.client.Minecraft surfaceIsInvalid\n")
                .file("neoforge.mixins.json", "{\"package\":\"net.neoforged.neoforge.mixins\",\"mixins\":[]}")
                .file(HOLDER + ".class", emptyClass(HOLDER, "java/lang/Object",
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT))
                .writeTo(dir.resolve("neoforge-universal.jar"));
    }

    @Test
    @DisplayName("the API's injected interfaces, access transformers and mixin config are all read")
    void readsWhatTheApiPublishes() throws Exception {
        LoaderApiPatches patches = LoaderApiPatches.read(List.of(apiJar()));

        assertTrue(patches.accessRules().interfacesFor("net/minecraft/world/item/ItemStack").stream()
                .anyMatch(added -> added.rawName().endsWith("IItemStackExtension")));
        assertTrue(patches.accessRules().touches("net/minecraft/client/Minecraft"));
        assertEquals(List.of("neoforge.mixins.json"), patches.mixinConfigs());
        assertNotNull(patches.rebaser(), "the jar has AttachmentHolder, so Entity is rebased onto it");
    }

    @Test
    @DisplayName("generics written the way Java source writes them become a class signature's")
    void genericsBecomeSignatureForm() {
        assertEquals("a/IHolderExtension<TT;>", LoaderApiPatches.signatureForm("a/IHolderExtension<T>"));
        assertEquals("a/Pair<TK;TV;>", LoaderApiPatches.signatureForm("a/Pair<K, V>"));
        assertEquals("a/Plain", LoaderApiPatches.signatureForm("a/Plain"));

        InjectedInterface parsed = InjectedInterface.parse(LoaderApiPatches.signatureForm("a/IHolderExtension<T>"));
        assertEquals("a/IHolderExtension", parsed.rawName());
    }

    @Test
    @DisplayName("a game class is rebased onto the API superclass, and still constructs")
    void rebasedClassConstructs() throws Exception {
        Transformer rebaser = LoaderApiPatches.read(List.of(apiJar())).rebaser();
        byte[] rebased = rebaser.transform(ENTITY, vanillaEntity(), TransformContext.of(null, name -> true));

        Map<String, byte[]> classes = Map.of(ENTITY, rebased, HOLDER, emptyClass(HOLDER, "java/lang/Object",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT));
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name.replace('.', '/'));

                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }

                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        Class<?> entity = loader.loadClass(ENTITY.replace('/', '.'));
        assertEquals(HOLDER.replace('/', '.'), entity.getSuperclass().getName());

        Object made = entity.getConstructor().newInstance();
        assertNotNull(entity.getField("lock").get(made), "the Object the constructor makes is still made");
    }

    @Test
    @DisplayName("a jar that publishes nothing changes nothing")
    void emptyJarChangesNothing() throws Exception {
        LoaderApiPatches patches = LoaderApiPatches.read(List.of(new JarBuilder().file("x.txt", "x")
                .writeTo(dir.resolve("other.jar"))));

        assertNull(patches.rebaser());
        assertFalse(patches.accessRules().touches("net/minecraft/world/item/ItemStack"));
        assertTrue(patches.mixinConfigs().isEmpty());
    }
}
