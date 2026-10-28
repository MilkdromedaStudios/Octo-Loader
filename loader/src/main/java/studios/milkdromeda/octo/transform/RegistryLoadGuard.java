package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Makes one data pack entry that cannot be read fail as one entry.
 *
 * <p>The game reads each recipe, loot table and worldgen entry through
 * {@code RegistryLoadTask.PendingRegistration.loadFromResource}, which turns a
 * parse failure into an error for that entry — but catches only
 * {@code Exception}. A translated mod's codec can fail with a linkage error
 * instead: Create's {@code ItemCopyingRecipe} reached a constructor 26.x does
 * not have, and the {@code NoSuchMethodError} went past the catch and ended
 * the whole data pack load, and with it the server. Here the method is kept
 * under another name and called from one that also turns what else is thrown
 * into the same per-entry error.
 */
public final class RegistryLoadGuard implements Transformer {
    private static final String OWNER = "net/minecraft/resources/RegistryLoadTask$PendingRegistration";
    private static final String NAME = "loadFromResource";
    private static final String DESCRIPTOR = "(Lcom/mojang/serialization/Decoder;Lnet/minecraft/resources/RegistryOps;"
            + "Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/server/packs/resources/Resource;)"
            + "Lcom/mojang/datafixers/util/Either;";
    private static final String KEPT = "octo$loadFromResource";

    @Override
    public String name() {
        return "registry-load-guard";
    }

    private static final String TASK = "net/minecraft/resources/RegistryLoadTask";
    private static final String ERROR_SINK = "(L" + OWNER + ";Ljava/lang/Exception;)V";

    private static final String MAPPED_REGISTRY = "net/minecraft/core/MappedRegistry";

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(OWNER) || className.equals(TASK) || className.equals(MAPPED_REGISTRY);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        if (className.equals(MAPPED_REGISTRY)) {
            return fillingUnbound(bytes);
        }

        return className.equals(TASK) ? forgiving(bytes) : guarded(bytes);
    }

    /**
     * Before a registry is frozen, the loot tables mods named and never
     * supplied are filled with empty ones ({@code DataErrors.fillUnbound}):
     * one such name is otherwise a registry that will not freeze, and a server
     * that will not start.
     */
    private static byte[] fillingUnbound(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] found = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);

                if (delegate == null || !name.equals("freeze") || !descriptor.equals("()Lnet/minecraft/core/Registry;")
                        || (access & Opcodes.ACC_STATIC) != 0) {
                    return delegate;
                }

                found[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/bridge/DataErrors",
                                "fillUnbound", "(Ljava/lang/Object;)V", false);
                    }
                };
            }
        }, 0);

        return found[0] ? writer.toByteArray() : bytes;
    }

    /**
     * Where the task records an entry that could not be read: an entry from a
     * translated mod's pack is reported and left out instead, since one of
     * those is otherwise enough to stop every world from loading.
     */
    private static byte[] forgiving(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] found = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);

                if (delegate == null || !descriptor.equals(ERROR_SINK) || (access & Opcodes.ACC_STATIC) != 0
                        || !name.startsWith("lambda$registerElements")) {
                    return delegate;
                }

                found[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label keep = new Label();
                        super.visitVarInsn(Opcodes.ALOAD, 2);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/bridge/DataErrors",
                                "forgive", "(Ljava/lang/Throwable;)Z", false);
                        super.visitJumpInsn(Opcodes.IFEQ, keep);
                        super.visitInsn(Opcodes.RETURN);
                        super.visitLabel(keep);
                        super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    }
                };
            }
        }, 0);

        return found[0] ? writer.toByteArray() : bytes;
    }

    private static byte[] guarded(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] found = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (name.equals(NAME) && descriptor.equals(DESCRIPTOR) && (access & Opcodes.ACC_STATIC) != 0) {
                    found[0] = true;
                    return super.visitMethod(access, KEPT, descriptor, signature, exceptions);
                }

                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                if (found[0]) {
                    writeGuard(cv);
                }

                super.visitEnd();
            }
        }, 0);

        return found[0] ? writer.toByteArray() : bytes;
    }

    private static void writeGuard(ClassVisitor writer) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, NAME, DESCRIPTOR, null,
                null);
        method.visitCode();
        Label start = new Label();
        Label end = new Label();
        Label handler = new Label();
        method.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
        method.visitLabel(start);

        for (int i = 0; i < 4; i++) {
            method.visitVarInsn(Opcodes.ALOAD, i);
        }

        method.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, KEPT, DESCRIPTOR, false);
        method.visitLabel(end);
        method.visitInsn(Opcodes.ARETURN);

        Object[] locals = { "com/mojang/serialization/Decoder", "net/minecraft/resources/RegistryOps",
                "net/minecraft/resources/ResourceKey", "net/minecraft/server/packs/resources/Resource" };
        method.visitLabel(handler);
        method.visitFrame(Opcodes.F_NEW, 4, locals, 1, new Object[] { "java/lang/Throwable" });
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/bridge/DataErrors", "entryFailed",
                "(Ljava/lang/Throwable;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        method.visitTypeInsn(Opcodes.CHECKCAST, "com/mojang/datafixers/util/Either");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }
}
