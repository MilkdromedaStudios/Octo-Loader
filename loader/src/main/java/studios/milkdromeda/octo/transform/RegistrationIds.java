package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Gives blocks and items built by mods from before 1.21.2 the id they now
 * have to carry, through {@link studios.milkdromeda.octo.bridge.RegistrationContext}.
 *
 * <p>Three hooks: NeoForge's {@code RegisterEvent.register} records which
 * entry it is building, and the {@code BlockBehaviour} and {@code Item}
 * constructors, before anything else, give properties without an id the one
 * being registered.
 */
public final class RegistrationIds implements Transformer {
    private static final String CONTEXT = "studios/milkdromeda/octo/bridge/RegistrationContext";
    private static final String REGISTER_EVENT = "net/neoforged/neoforge/registries/RegisterEvent";
    private static final String BLOCK = "net/minecraft/world/level/block/state/BlockBehaviour";
    private static final String ITEM = "net/minecraft/world/item/Item";

    @Override
    public String name() {
        return "registration-ids";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(REGISTER_EVENT) || className.equals(BLOCK) || className.equals(ITEM);
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

                if (delegate == null) {
                    return delegate;
                }

                if (className.equals(REGISTER_EVENT) && name.equals("register") && descriptor.equals(
                        "(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/resources/Identifier;"
                                + "Ljava/util/function/Supplier;)V")) {
                    changed[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 3);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT, "wrap",
                                    "(Ljava/util/function/Supplier;Ljava/lang/Object;Ljava/lang/Object;)"
                                            + "Ljava/util/function/Supplier;", false);
                            super.visitVarInsn(Opcodes.ASTORE, 3);
                        }
                    };
                }

                String registry = className.equals(BLOCK) ? "block" : className.equals(ITEM) ? "item" : null;

                if (registry != null && name.equals("<init>") && descriptor.equals("(L" + className + "$Properties;)V")) {
                    changed[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // Before super(): only the argument is touched, never this.
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitLdcInsn(registry);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, CONTEXT, "ensureId",
                                    "(Ljava/lang/Object;Ljava/lang/String;)V", false);
                        }
                    };
                }

                return delegate;
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
