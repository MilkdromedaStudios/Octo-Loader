package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Where the game loads its models, the calls that load the ones mods ask
 * for by name with them ({@link studios.milkdromeda.octo.bridge.client.ExtraModels}).
 *
 * <p>Three points, each at a method the model reload passes through once:
 * {@code ModelDiscovery.resolve()} as it starts, to add the mods' models as
 * roots; {@code ModelBakery.bakeModels} as it starts, for the bakery and its
 * materials; and {@code ModelManager.apply} as it returns, to bake the mods'
 * models and tell the mods. Each call is a static one with what the method
 * already holds, so the game's own work is untouched.
 */
public final class ExtraModelHooks implements Transformer {
    private static final String HELPER = "studios/milkdromeda/octo/bridge/client/ExtraModels";
    private static final String PACKAGE = "net/minecraft/client/resources/model/";

    @Override
    public String name() {
        return "extra-model-hooks";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(PACKAGE + "ModelDiscovery") || className.equals(PACKAGE + "ModelBakery")
                || className.equals(PACKAGE + "ModelManager");
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

                if (delegate == null || (access & Opcodes.ACC_STATIC) != 0) {
                    return delegate;
                }

                if (className.endsWith("ModelDiscovery") && name.equals("resolve")
                        && descriptor.equals("()Ljava/util/Map;")) {
                    changed[0] = true;
                    return atStart(delegate, "discover", "(Ljava/lang/Object;)V", 0);
                }

                if (className.endsWith("ModelBakery") && name.equals("bakeModels")
                        && descriptor.startsWith("(L" + PACKAGE + "sprite/MaterialBaker;")) {
                    changed[0] = true;
                    return atStart(delegate, "baking", "(Ljava/lang/Object;Ljava/lang/Object;)V", 0, 1);
                }

                if (className.endsWith("ModelManager") && name.equals("apply")
                        && descriptor.equals("(L" + PACKAGE + "ModelManager$ReloadState;)V")) {
                    changed[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "applied",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                            }

                            super.visitInsn(opcode);
                        }
                    };
                }

                return delegate;
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }

    private static MethodVisitor atStart(MethodVisitor delegate, String hook, String descriptor, int... slots) {
        return new MethodVisitor(Opcodes.ASM9, delegate) {
            @Override
            public void visitCode() {
                super.visitCode();

                for (int slot : slots) {
                    super.visitVarInsn(Opcodes.ALOAD, slot);
                }

                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, hook, descriptor, false);
            }
        };
    }
}
