package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Binds each registry entry to its value as it is registered, as the game
 * NeoForge and Forge patch does.
 *
 * <p>Since 26.x the game binds a registered entry's holder only when its
 * registry is frozen. Blocks and items are bound from the start — their
 * holders are made with them — but a sound event or a data component type a
 * mod registered is not, and a mod reads its own entries while registering
 * the next: Farmer's Delight builds its fence gate with its sound events,
 * Supplementaries its items with its data components, and each such read was
 * "Trying to access unbound value" and an entry missing from the game. The
 * holder is bound at once; the freeze binds it to the same value again.
 */
public final class EagerHolderBinding implements Transformer {
    private static final String REGISTRY = "net/minecraft/core/MappedRegistry";
    private static final String REFERENCE = "net/minecraft/core/Holder$Reference";
    private static final String REGISTER = "(Lnet/minecraft/resources/ResourceKey;Ljava/lang/Object;"
            + "Lnet/minecraft/core/RegistrationInfo;)L" + REFERENCE + ";";

    @Override
    public String name() {
        return "eager-holder-binding";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(REGISTRY);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        boolean[] changed = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);

                if (delegate == null || !name.equals("register") || !descriptor.equals(REGISTER)
                        || (access & Opcodes.ACC_STATIC) != 0) {
                    return delegate;
                }

                changed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            super.visitInsn(Opcodes.DUP);
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REFERENCE, "bindValue",
                                    "(Ljava/lang/Object;)V", false);
                        }

                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
