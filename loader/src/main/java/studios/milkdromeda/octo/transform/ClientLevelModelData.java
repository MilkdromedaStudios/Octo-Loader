package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The model data store NeoForge's patch gives the client's level, added back.
 *
 * <p>NeoForge's {@code ILevelExtension.getModelDataManager()} answers null by
 * default, and its patched {@code ClientLevel} answers a
 * {@code ModelDataManager} of its own. On the unpatched game every level
 * answered null, and Sodium, which asks for it for every chunk section it
 * builds, ended the game on a world's first frame.
 *
 * <p>So {@code ClientLevel} gets that method back, with NeoForge's own manager
 * made the first time it is asked for. A server's levels keep the default,
 * as they do under NeoForge.
 */
public final class ClientLevelModelData implements Transformer {
    static final String LEVEL = "net/minecraft/client/multiplayer/ClientLevel";
    static final String MANAGER = "net/neoforged/neoforge/model/data/ModelDataManager";
    private static final String METHOD = "getModelDataManager";
    private static final String DESCRIPTOR = "()L" + MANAGER + ";";
    private static final String FIELD = "octo$modelDataManager";

    @Override
    public String name() {
        return "client-level-model-data";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(LEVEL);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        boolean[] declared = new boolean[1];

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (name.equals(METHOD) && descriptor.equals(DESCRIPTOR)) {
                    declared[0] = true;
                }

                return null;
            }
        }, ClassReader.SKIP_CODE);

        if (declared[0]) {
            return bytes;
        }

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitEnd() {
                cv.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, FIELD, "L" + MANAGER + ";", null, null)
                        .visitEnd();

                MethodVisitor method = cv.visitMethod(Opcodes.ACC_PUBLIC, METHOD, DESCRIPTOR, null, null);
                method.visitCode();
                Label made = new Label();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(Opcodes.GETFIELD, LEVEL, FIELD, "L" + MANAGER + ";");
                method.visitJumpInsn(Opcodes.IFNONNULL, made);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitTypeInsn(Opcodes.NEW, MANAGER);
                method.visitInsn(Opcodes.DUP);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, MANAGER, "<init>",
                        "(Lnet/minecraft/world/level/Level;)V", false);
                method.visitFieldInsn(Opcodes.PUTFIELD, LEVEL, FIELD, "L" + MANAGER + ";");
                method.visitLabel(made);
                method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(Opcodes.GETFIELD, LEVEL, FIELD, "L" + MANAGER + ";");
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
                super.visitEnd();
            }
        }, 0);

        return writer.toByteArray();
    }
}
