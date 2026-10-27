package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Lets a mod from before 26.x make an item stack while it registers.
 *
 * <p>Since 26.1 an item's components are bound when the server loads its
 * data, long after registration, and {@code Holder.Reference.components()}
 * fails until then. Mods written earlier make stacks from static initialisers
 * — Create's display sources hold {@code new ItemStack(Items.ENCHANTING_TABLE)},
 * its advancements one icon each — and every one of those failed, taking the
 * entry it was part of with it.
 *
 * <p>So {@code components()} answers an unbound holder with
 * {@link studios.milkdromeda.octo.bridge.LateComponents a view} of the
 * components it will have: empty until they are bound, and then theirs.
 */
public final class UnboundComponents implements Transformer {
    private static final String REFERENCE = "net/minecraft/core/Holder$Reference";
    private static final String MAP = "net/minecraft/core/component/DataComponentMap";

    @Override
    public String name() {
        return "unbound-components";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(REFERENCE);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] changed = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            private boolean hasField;

            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String descriptor,
                    String signature, Object value) {
                hasField |= name.equals("components") && descriptor.equals("L" + MAP + ";");
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (!hasField || !name.equals("components") || !descriptor.equals("()L" + MAP + ";")
                        || (access & Opcodes.ACC_STATIC) != 0) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }

                // The body is replaced: the bound map, or the view in its place.
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                method.visitCode();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(Opcodes.GETFIELD, REFERENCE, "components", "L" + MAP + ";");
                method.visitMethodInsn(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/bridge/LateComponents",
                        "components", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                method.visitTypeInsn(Opcodes.CHECKCAST, MAP);
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
                changed[0] = true;
                return null;
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
