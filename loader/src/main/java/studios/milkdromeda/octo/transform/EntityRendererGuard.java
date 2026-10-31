package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * An entity with no renderer is not drawn, rather than ending the game.
 *
 * <p>The game takes for granted that every entity type has a renderer, and
 * asks the one it gets whether to draw before checking there is one: an
 * entity a mod registered and never gave a renderer — or whose renderer
 * could not be made here — was a {@code NullPointerException} in the middle
 * of a frame, and the client crashed the moment one came into view. It is
 * named once in the log ({@code studios.milkdromeda.octo.bridge.client.MissingRenderers})
 * and skipped.
 */
public final class EntityRendererGuard implements Transformer {
    private static final String DISPATCHER = "net/minecraft/client/renderer/entity/EntityRenderDispatcher";
    private static final String SHOULD_RENDER = "(Lnet/minecraft/world/entity/Entity;"
            + "Lnet/minecraft/client/renderer/culling/Frustum;DDDF)Z";

    @Override
    public String name() {
        return "entity-renderer-guard";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(DISPATCHER);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);

                if (!name.equals("shouldRender") || !descriptor.equals(SHOULD_RENDER)) {
                    return delegate;
                }

                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label known = new Label();
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DISPATCHER, "getRenderer",
                                "(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/client/renderer/entity/EntityRenderer;",
                                false);
                        super.visitJumpInsn(Opcodes.IFNONNULL, known);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "studios/milkdromeda/octo/bridge/client/MissingRenderers", "report",
                                "(Ljava/lang/Object;)V", false);
                        super.visitInsn(Opcodes.ICONST_0);
                        super.visitInsn(Opcodes.IRETURN);
                        super.visitLabel(known);
                        super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    }
                };
            }
        }, 0);

        return writer.toByteArray();
    }
}
