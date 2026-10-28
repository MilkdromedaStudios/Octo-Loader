package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Keeps a null out of a {@code CompoundTag}.
 *
 * <p>A translated mod saves what a removed helper hands back —
 * {@code compound.put("Item", stack.saveOptional(registries))} — and a
 * helper that is bypassed hands back null. The compound takes it, and fails
 * only later, when it is written: the chunk it belongs to cannot be sent or
 * saved, and the player is disconnected. A null put is left out instead.
 */
public final class NullTagGuard implements Transformer {
    private static final String COMPOUND = "net/minecraft/nbt/CompoundTag";
    private static final String PUT = "(Ljava/lang/String;Lnet/minecraft/nbt/Tag;)Lnet/minecraft/nbt/Tag;";

    @Override
    public String name() {
        return "null-tag-guard";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(COMPOUND);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] found = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);

                if (delegate == null || !name.equals("put") || !descriptor.equals(PUT)
                        || (access & Opcodes.ACC_STATIC) != 0) {
                    return delegate;
                }

                found[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label present = new Label();
                        super.visitVarInsn(Opcodes.ALOAD, 2);
                        super.visitJumpInsn(Opcodes.IFNONNULL, present);
                        super.visitInsn(Opcodes.ACONST_NULL);
                        super.visitInsn(Opcodes.ARETURN);
                        super.visitLabel(present);
                        super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    }
                };
            }
        }, 0);

        return found[0] ? writer.toByteArray() : bytes;
    }
}
