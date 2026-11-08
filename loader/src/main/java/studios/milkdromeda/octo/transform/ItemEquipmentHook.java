package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Hands the component initializer each {@code Item} makes for itself to
 * {@link studios.milkdromeda.octo.bridge.ItemEquipment}, before the item
 * registers it: an item that says where it is worn the old way is given the
 * {@code equippable} component there.
 */
public final class ItemEquipmentHook implements Transformer {
    static final String ITEM = "net/minecraft/world/item/Item";
    private static final String CONSTRUCTOR = "(Lnet/minecraft/world/item/Item$Properties;)V";
    private static final String FINALIZE = "finalizeInitializer";
    private static final String INITIALIZER = "net/minecraft/core/component/DataComponentInitializers$Initializer";
    private static final String BRIDGE = "studios/milkdromeda/octo/bridge/ItemEquipment";

    @Override
    public String name() {
        return "item-equipment";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(ITEM);
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

                if (!name.equals("<init>") || !descriptor.equals(CONSTRUCTOR)) {
                    return delegate;
                }

                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private boolean finalized;

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc,
                            boolean isInterface) {
                        super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                        finalized = method.equals(FINALIZE) && !changed[0];
                    }

                    @Override
                    public void visitVarInsn(int opcode, int slot) {
                        super.visitVarInsn(opcode, slot);

                        if (finalized && opcode == Opcodes.ASTORE) {
                            // initializer = ItemEquipment.withOldSlot(initializer, this): no branch,
                            // so the frames stay as the game wrote them.
                            super.visitVarInsn(Opcodes.ALOAD, slot);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "withOldSlot",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitTypeInsn(Opcodes.CHECKCAST, INITIALIZER);
                            super.visitVarInsn(Opcodes.ASTORE, slot);
                            changed[0] = true;
                        }

                        finalized = false;
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        finalized = false;
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
