package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A model face whose UVs run past its texture, baked as it was before 26.x.
 *
 * <p>Until 26.x a UV beyond 16 simply sampled past the edge. 26.x works out
 * each face's transparency from the pixels it covers, and refuses a region
 * outside the image — {@code Cannot compute translucency out of bounds:
 * [14, 4, 20, 10] in 16x16 image} — which failed the whole model: Create's
 * creative motor was the missing-texture cube. The region asked about is
 * clamped to the image, so the face is judged by the pixels it really has.
 */
public final class TextureBounds implements Transformer {
    private static final String IMAGE = "com/mojang/blaze3d/platform/NativeImage";

    @Override
    public String name() {
        return "texture-bounds";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(IMAGE);
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

                if (!name.equals("computeTransparency") || !descriptor.startsWith("(IIII)")) {
                    return delegate;
                }

                changed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        clamp(1, "width");
                        clamp(2, "height");
                        clamp(3, "width");
                        clamp(4, "height");
                    }

                    /** {@code slot = Math.min(Math.max(slot, 0), this.<limit>)} */
                    private void clamp(int slot, String limit) {
                        super.visitVarInsn(Opcodes.ILOAD, slot);
                        super.visitInsn(Opcodes.ICONST_0);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false);
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitFieldInsn(Opcodes.GETFIELD, IMAGE, limit, "I");
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
                        super.visitVarInsn(Opcodes.ISTORE, slot);
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
