package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The shaped pattern's size checks, reading the size mods may have raised
 * ({@link studios.milkdromeda.octo.bridge.CraftingSize}) instead of the 3
 * compiled into them: the first is the rows', the second each row's width.
 */
public final class CraftingSizeHook implements Transformer {
    private static final String DATA = "net/minecraft/world/item/crafting/ShapedRecipePattern$Data";
    private static final String HELPER = "studios/milkdromeda/octo/bridge/CraftingSize";

    @Override
    public String name() {
        return "crafting-size";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(DATA);
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

                // The pattern's validation: the only method that compares a
                // list's size and each row's length with 3.
                if (!descriptor.equals("(Ljava/util/List;)Lcom/mojang/serialization/DataResult;")) {
                    return delegate;
                }

                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    private int seen;

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ICONST_3 && seen < 2) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, seen++ == 0 ? "height" : "width",
                                    "()I", false);
                            changed[0] = true;
                            return;
                        }

                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
