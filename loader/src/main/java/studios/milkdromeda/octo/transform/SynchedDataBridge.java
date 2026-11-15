package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Gives an entity from before 1.20.5 the {@code defineSynchedData(Builder)}
 * the game calls now, running the {@code defineSynchedData()} it has.
 *
 * <p>Without it not one such entity could exist: the game built it, asked it
 * to define its data through a method it does not override, and refused it at
 * the first value it read — "has not defined synched data value 18" — for
 * every mob Wither Storm adds. See {@link studios.milkdromeda.octo.bridge.EntityData}.
 */
public final class SynchedDataBridge implements Transformer {
    private static final String NAME = "defineSynchedData";
    private static final String OLD = "()V";
    private static final String NEW = "(Lnet/minecraft/network/syncher/SynchedEntityData$Builder;)V";
    private static final String HELPER = "studios/milkdromeda/octo/bridge/EntityData";
    private static final ByteScan NAMES = new ByteScan(java.util.List.of(NAME));

    private final MemberIndex index;

    public SynchedDataBridge(MemberIndex index) {
        this.index = index;
    }

    @Override
    public String name() {
        return "synched-data-bridge";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() != null && context.mod().isFromAnotherVersion();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        if (!NAMES.matches(bytes)) {
            return bytes;
        }

        ClassReader reader = new ClassReader(bytes);
        String superName = reader.getSuperName();
        boolean[] declares = new boolean[2];

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (name.equals(NAME) && (access & Opcodes.ACC_STATIC) == 0) {
                    declares[0] |= descriptor.equals(OLD) && (access & Opcodes.ACC_ABSTRACT) == 0;
                    declares[1] |= descriptor.equals(NEW);
                }

                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // Only an entity's: something above it must take the builder.
        if (!declares[0] || declares[1] || superName == null || !index.hasMethod(superName, NAME, NEW)) {
            return bytes;
        }

        Integer above = index.methodAccess(superName, NAME, NEW);
        boolean callsSuper = above != null && (above & Opcodes.ACC_ABSTRACT) == 0;
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                MethodVisitor method = super.visitMethod(Opcodes.ACC_PROTECTED, NAME, NEW, null, null);
                method.visitCode();

                if (callsSuper) {
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitMethodInsn(Opcodes.INVOKESPECIAL, superName, NAME, NEW, false);
                }

                Label from = new Label();
                Label to = new Label();
                Label handler = new Label();
                method.visitTryCatchBlock(from, to, handler, null);
                method.visitVarInsn(Opcodes.ALOAD, 1);
                method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "enter", "(Ljava/lang/Object;)V", false);
                method.visitLabel(from);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                // This class's own, whatever a subclass overrides it with:
                // each class's bridge runs that class's method.
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, className, NAME, OLD, false);
                method.visitLabel(to);
                method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "exit", "()V", false);
                method.visitInsn(Opcodes.RETURN);
                method.visitLabel(handler);
                method.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] { "java/lang/Throwable" });
                method.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "exit", "()V", false);
                method.visitInsn(Opcodes.ATHROW);
                method.visitMaxs(0, 0);
                method.visitEnd();
                super.visitEnd();
            }
        }, 0);

        context.note("its entities define their synced data as this Minecraft asks");
        return writer.toByteArray();
    }
}
