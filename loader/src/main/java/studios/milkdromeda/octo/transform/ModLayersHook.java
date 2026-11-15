package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * {@code LayerDefinitions.createRoots} answering with mods' model layers too
 * ({@link studios.milkdromeda.octo.bridge.client.ModLayers}), as the loaders'
 * patch to the class makes it.
 */
public final class ModLayersHook implements Transformer {
    private static final String LAYERS = "net/minecraft/client/model/geom/LayerDefinitions";
    private static final String HELPER = "studios/milkdromeda/octo/bridge/client/ModLayers";

    @Override
    public String name() {
        return "mod-layers";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(LAYERS);
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

                if (!name.equals("createRoots") || !descriptor.equals("()Ljava/util/Map;")) {
                    return delegate;
                }

                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "withMods",
                                    "(Ljava/util/Map;)Ljava/util/Map;", false);
                            changed[0] = true;
                        }

                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
