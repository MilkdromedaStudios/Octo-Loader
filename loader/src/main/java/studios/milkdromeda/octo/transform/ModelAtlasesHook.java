package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Hands each atlas's sprite sources, as {@code SpriteSourceList.load}
 * returns them, to {@link studios.milkdromeda.octo.bridge.client.ModelAtlases},
 * which stitches older mods' item textures with the blocks.
 */
public final class ModelAtlasesHook implements Transformer {
    static final String SPRITE_SOURCE_LIST = "net/minecraft/client/renderer/texture/atlas/SpriteSourceList";
    private static final String LOAD = "(Lnet/minecraft/server/packs/resources/ResourceManager;"
            + "Lnet/minecraft/resources/Identifier;)L" + SPRITE_SOURCE_LIST + ";";
    private static final String BRIDGE = "studios/milkdromeda/octo/bridge/client/ModelAtlases";

    @Override
    public String name() {
        return "model-atlases";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(SPRITE_SOURCE_LIST);
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

                if (!name.equals("load") || !descriptor.equals(LOAD) || (access & Opcodes.ACC_STATIC) == 0) {
                    return delegate;
                }

                changed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.ARETURN) {
                            // The list is on the stack: hand it over with the atlas it is for.
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "withLegacyItems",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitTypeInsn(Opcodes.CHECKCAST, SPRITE_SOURCE_LIST);
                        }

                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
