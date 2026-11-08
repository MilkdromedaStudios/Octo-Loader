package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Has the game's HUD, as it finishes collecting what to draw each frame, draw
 * the layers NeoForge mods registered
 * ({@link studios.milkdromeda.octo.bridge.client.ModHudLayers}).
 */
public final class ModHudLayersHook implements Transformer {
    static final String HUD = "net/minecraft/client/gui/Hud";
    private static final String EXTRACT = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
            + "Lnet/minecraft/client/DeltaTracker;)V";
    private static final String BRIDGE = "studios/milkdromeda/octo/bridge/client/ModHudLayers";

    @Override
    public String name() {
        return "mod-hud-layers";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(HUD);
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

                if (!name.equals("extractRenderState") || !descriptor.equals(EXTRACT)
                        || (access & Opcodes.ACC_STATIC) != 0) {
                    return delegate;
                }

                changed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            // ModHudLayers.extract(this, extractor, deltaTracker): no branch, so the
                            // frames stay as the game wrote them.
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitVarInsn(Opcodes.ALOAD, 2);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "extract",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                        }

                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
