package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Steps a translated mod's method out of the way of one the game has made final.
 *
 * <p>Mojang finalises methods that mods used to override: {@code Item.getDescriptionId()}
 * is final in 26.x, its answer coming from the item's components, and Create's
 * {@code BlazeBurnerBlockItem} overriding it is a class the JVM refuses to
 * define, which took every block Create registers down with it. The mod's
 * method is renamed, so it no longer overrides anything: the class loads, the
 * game's own method answers, and only that one customisation is lost.
 */
public final class FinalOverrides implements Transformer {
    private static final OctoLog LOG = OctoLog.of(FinalOverrides.class);
    static final String PREFIX = "octo$final$";

    private final MemberIndex index;

    public FinalOverrides(MemberIndex index) {
        this.index = index;
    }

    @Override
    public String name() {
        return "final-overrides";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() != null && context.mod().isFromAnotherVersion();
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        String superName = reader.getSuperName();

        if (superName == null || superName.equals("java/lang/Object")
                || (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(reader, 0);
        boolean[] changed = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0 && !name.startsWith("<")
                        && overridesFinal(superName, name, descriptor)) {
                    changed[0] = true;
                    LOG.info("{}.{} overrides a method this Minecraft has made final; it is kept under another "
                            + "name and the game's own answers", className.replace('/', '.'), name);
                    return super.visitMethod(access, PREFIX + name, descriptor, signature, exceptions);
                }

                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }

    private boolean overridesFinal(String superName, String name, String descriptor) {
        Integer access = index.methodAccess(superName, name, descriptor);
        return access != null && (access & Opcodes.ACC_FINAL) != 0 && (access & Opcodes.ACC_PRIVATE) == 0;
    }
}
