package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * {@code DefaultAttributes.getSupplier} and {@code hasSupplier} answering for
 * the mobs NeoForge mods gave attributes to
 * ({@link studios.milkdromeda.octo.bridge.forge.ModAttributes}), as NeoForge's
 * patch to the class makes them.
 */
public final class ModAttributesHook implements Transformer {
    private static final String DEFAULTS = "net/minecraft/world/entity/ai/attributes/DefaultAttributes";
    private static final String HELPER = "studios/milkdromeda/octo/bridge/forge/ModAttributes";
    private static final String SUPPLIER = "net/minecraft/world/entity/ai/attributes/AttributeSupplier";

    @Override
    public String name() {
        return "mod-attributes";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(DEFAULTS);
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
                boolean getter = name.equals("getSupplier") && descriptor.endsWith(")L" + SUPPLIER + ";");
                boolean test = name.equals("hasSupplier") && descriptor.endsWith(")Z");

                if ((access & Opcodes.ACC_STATIC) == 0 || !getter && !test
                        || !descriptor.startsWith("(Lnet/minecraft/world/entity/EntityType;)")) {
                    return delegate;
                }

                changed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label vanilla = new Label();
                        super.visitVarInsn(Opcodes.ALOAD, 0);

                        if (getter) {
                            // A mod's attributes, if it gave any; the game's if not.
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "supplierFor",
                                    "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitInsn(Opcodes.DUP);
                            super.visitJumpInsn(Opcodes.IFNULL, vanilla);
                            super.visitTypeInsn(Opcodes.CHECKCAST, SUPPLIER);
                            super.visitInsn(Opcodes.ARETURN);
                            super.visitLabel(vanilla);
                            super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] { "java/lang/Object" });
                            super.visitInsn(Opcodes.POP);
                        } else {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "has", "(Ljava/lang/Object;)Z",
                                    false);
                            super.visitJumpInsn(Opcodes.IFEQ, vanilla);
                            super.visitInsn(Opcodes.ICONST_1);
                            super.visitInsn(Opcodes.IRETURN);
                            super.visitLabel(vanilla);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        }
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
