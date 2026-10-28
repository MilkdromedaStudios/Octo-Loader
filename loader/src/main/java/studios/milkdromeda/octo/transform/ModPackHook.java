package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Hands every pack repository the game builds to
 * {@link studios.milkdromeda.octo.bridge.ModPacks}, as it finishes being
 * built, so Forge and NeoForge mods' jars are among its packs.
 */
public final class ModPackHook implements Transformer {
    private static final String REPOSITORY = "net/minecraft/server/packs/repository/PackRepository";

    @Override
    public String name() {
        return "mod-pack-hook";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(REPOSITORY);
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

                if (delegate == null || !name.equals("<init>")) {
                    return delegate;
                }

                changed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/bridge/ModPacks",
                                    "attach", "(Ljava/lang/Object;)V", false);
                        }

                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
