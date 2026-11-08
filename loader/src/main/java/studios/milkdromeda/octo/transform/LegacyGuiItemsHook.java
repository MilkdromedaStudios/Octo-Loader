package studios.milkdromeda.octo.transform;

import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Items catnip draws into screens and the HUD, drawn the way 26.x draws one.
 *
 * <p>Catnip's {@code GuiGameElement} (Create's and Ponder's library) drew an
 * item in a screen by setting up the 3D item pipeline by hand: the item
 * renderer's model, its texture manager through an accessor mixin, the
 * render system's blend and lighting state, a buffer source flushed at once.
 * None of that exists in 26.x, and the first step failed: the icon on
 * Create's goggles overlay ended the whole overlay. Its
 * {@code renderItemIntoGUI(pose, stack, flat)} is given a body that draws the
 * item into the frame being collected, where the pose it was handed puts it
 * ({@code GuiGraphics.octo$item}).
 */
public final class LegacyGuiItemsHook implements Transformer {
    private static final Set<String> OWNERS = Set.of(
            "net/createmod/catnip/gui/element/GuiGameElement$GuiItemRenderBuilder",
            "com/simibubi/create/foundation/gui/element/GuiGameElement$GuiItemRenderBuilder");
    private static final String METHOD = "renderItemIntoGUI";
    private static final String DESCRIPTOR = "(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/world/item/ItemStack;Z)V";

    @Override
    public String name() {
        return "legacy-gui-items";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return OWNERS.contains(className);
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
                if (!name.equals(METHOD) || !descriptor.equals(DESCRIPTOR) || (access & Opcodes.ACC_STATIC) == 0) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }

                changed[0] = true;
                // The mod's own body is kept, under another name, for anyone who looks.
                return super.visitMethod(access, "octo$" + name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                if (changed[0]) {
                    MethodVisitor method = super.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, METHOD,
                            DESCRIPTOR, null, null);
                    method.visitCode();
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/client/gui/GuiGraphics", "octo$item",
                            "(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemStack;)V", false);
                    method.visitInsn(Opcodes.RETURN);
                    method.visitMaxs(0, 0);
                    method.visitEnd();
                }

                super.visitEnd();
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
