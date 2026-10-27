package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Gives the game's enums the {@code getExtensionInfo()} NeoForge reads.
 *
 * <p>FML's launcher adds it to every enum mods may extend, and NeoForge's
 * {@code CommonHooks} asks {@code RecipeBookType} for it as it initialises,
 * right after registration. On the unpatched game the method is not there,
 * and the {@code NoSuchMethodError} ended NeoForge's registration before its
 * last steps: entity attributes, spawn placements, the blocks of block entity
 * types, creative tab order, the recipe property sets. Octo extends no enum,
 * so each answers that it was not extended.
 */
public final class ExtensibleEnums implements Transformer {
    private static final String INFO = "net/neoforged/fml/common/asm/enumextension/ExtensionInfo";
    private static final String GETTER = "getExtensionInfo";

    @Override
    public String name() {
        return "extensible-enums";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.startsWith("net/minecraft/");
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);

        if ((reader.getAccess() & Opcodes.ACC_ENUM) == 0 || !"java/lang/Enum".equals(reader.getSuperName())) {
            return bytes;
        }

        boolean[] has = { false };
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                has[0] |= name.equals(GETTER) && descriptor.equals("()L" + INFO + ";");
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        if (has[0]) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                MethodVisitor method = cv.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, GETTER,
                        "()L" + INFO + ";", null, null);
                method.visitCode();
                method.visitLdcInsn(Type.getObjectType(className));
                method.visitMethodInsn(Opcodes.INVOKESTATIC, INFO, "nonExtended",
                        "(Ljava/lang/Class;)L" + INFO + ";", false);
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
                super.visitEnd();
            }
        }, 0);

        return writer.toByteArray();
    }
}
