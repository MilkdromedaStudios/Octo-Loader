package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class ClientLevelModelDataTest {
    private static final String LEVEL = "net/minecraft/world/level/Level";

    @Test
    void theClientLevelKeepsOneModelDataManagerOfItsOwn() throws Exception {
        byte[] level = shell(LEVEL, "java/lang/Object", null);
        byte[] client = shell(ClientLevelModelData.LEVEL, LEVEL, null);
        byte[] manager = shell(ClientLevelModelData.MANAGER, "java/lang/Object", "(L" + LEVEL + ";)V");

        byte[] patched = new ClientLevelModelData().transform(ClientLevelModelData.LEVEL, client,
                TransformContext.of(null, name -> true));

        Map<String, byte[]> classes = Map.of(LEVEL.replace('/', '.'), level,
                ClientLevelModelData.LEVEL.replace('/', '.'), patched,
                ClientLevelModelData.MANAGER.replace('/', '.'), manager);
        ClassLoader loader = new ClassLoader(null) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);

                if (bytes == null) {
                    throw new ClassNotFoundException(name);
                }

                return defineClass(name, bytes, 0, bytes.length);
            }
        };

        Object clientLevel = loader.loadClass(ClientLevelModelData.LEVEL.replace('/', '.'))
                .getConstructor().newInstance();
        var method = clientLevel.getClass().getMethod("getModelDataManager");
        Object first = method.invoke(clientLevel);

        assertNotNull(first);
        assertSame(first, method.invoke(clientLevel));
    }

    @Test
    void theGamesDynamicModelsCollectTheirPartsByPositionFromTheRandomSourceAlone() {
        String model = "net/minecraft/client/renderer/block/dispatch/WeightedVariants";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, model, null, "java/lang/Object", null);
        MethodVisitor own = writer.visitMethod(Opcodes.ACC_PUBLIC, "collectParts", DynamicModelParts.BY_RANDOM, null,
                null);
        own.visitCode();
        own.visitInsn(Opcodes.RETURN);
        own.visitMaxs(0, 0);
        own.visitEnd();
        writer.visitEnd();

        byte[] patched = new DynamicModelParts().transform(model, writer.toByteArray(),
                TransformContext.of(null, name -> true));
        java.util.List<String> calls = new java.util.ArrayList<>();
        new org.objectweb.asm.ClassReader(patched).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (!descriptor.equals(DynamicModelParts.BY_POSITION)) {
                    return null;
                }

                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String called, String calledDescriptor,
                            boolean onInterface) {
                        calls.add(owner + "." + called + calledDescriptor);
                    }
                };
            }
        }, 0);

        assertEquals(java.util.List.of(model + ".collectParts" + DynamicModelParts.BY_RANDOM), calls);
        // A second pass leaves NeoForge's own method alone.
        assertSame(patched, new DynamicModelParts().transform(model, patched, TransformContext.of(null, name -> true)));
    }

    @Test
    void theGamesFluidsAnswerTheFluidTypesNeoForgeRegistersForThem() {
        for (var entry : VanillaFluidTypes.TYPES.entrySet()) {
            byte[] patched = new VanillaFluidTypes().transform(entry.getKey(),
                    shell(entry.getKey(), "java/lang/Object", null), TransformContext.of(null, name -> true));
            java.util.List<String> reads = new java.util.ArrayList<>();
            new org.objectweb.asm.ClassReader(patched).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                        String[] exceptions) {
                    if (!descriptor.equals(VanillaFluidTypes.DESCRIPTOR)) {
                        return null;
                    }

                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitFieldInsn(int opcode, String owner, String field, String fieldDescriptor) {
                            reads.add(owner + "." + field);
                        }
                    };
                }
            }, 0);

            assertEquals(java.util.List.of("net/neoforged/neoforge/common/NeoForgeMod." + entry.getValue()), reads);
        }
    }

    /** A public class with a public constructor taking nothing, or the given arguments (which it ignores). */
    private static byte[] shell(String name, String superName, String constructor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superName, null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                constructor == null ? "()V" : constructor, null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
