package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashSet;
import java.util.Set;

/**
 * The capability listener methods NeoForge's patch adds to {@code ServerLevel},
 * added back and answered by {@link studios.milkdromeda.octo.bridge.forge.CapabilityListeners}.
 *
 * <p>NeoForge's own {@code BlockCapabilityCache} registers a listener for each
 * position it caches, and mods invalidate positions whose capabilities
 * change; on the unpatched level the first linked to nothing and the second
 * reached the extension interface's empty default.
 */
public final class ServerLevelCapabilities implements Transformer {
    private static final String LEVEL = "net/minecraft/server/level/ServerLevel";
    private static final String HELPER = "studios/milkdromeda/octo/bridge/forge/CapabilityListeners";
    private static final String POS = "net/minecraft/core/BlockPos";
    private static final String CHUNK_POS = "net/minecraft/world/level/ChunkPos";
    private static final String LISTENER = "net/neoforged/neoforge/capabilities/ICapabilityInvalidationListener";

    @Override
    public String name() {
        return "server-level-capabilities";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(LEVEL);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        Set<String> declared = new HashSet<>();

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                declared.add(name + descriptor);
                return null;
            }
        }, ClassReader.SKIP_CODE);

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                add(declared, "registerCapabilityListener", "(L" + POS + ";L" + LISTENER + ";)V", method -> {
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitVarInsn(Opcodes.ALOAD, 2);
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "register",
                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                });
                add(declared, "invalidateCapabilities", "(L" + POS + ";)V", method -> {
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "invalidate",
                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                });
                add(declared, "invalidateCapabilities", "(L" + CHUNK_POS + ";)V", method -> {
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CHUNK_POS, "x", "()I", false);
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CHUNK_POS, "z", "()I", false);
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "invalidateChunk",
                            "(Ljava/lang/Object;II)V", false);
                });
                add(declared, "cleanCapabilityListenerReferences", "()V", method -> {
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "clean", "(Ljava/lang/Object;)V", false);
                });
                super.visitEnd();
            }

            private void add(Set<String> present, String name, String descriptor,
                    java.util.function.Consumer<MethodVisitor> body) {
                if (present.contains(name + descriptor)) {
                    return;
                }

                MethodVisitor method = super.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
                method.visitCode();
                body.accept(method);
                method.visitInsn(Opcodes.RETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
            }
        }, 0);

        return writer.toByteArray();
    }
}
