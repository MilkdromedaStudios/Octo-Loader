package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Lets an older mod set a field of the game's that has since become final.
 *
 * <p>A container screen's size was the mod's to set until 26.x, where
 * {@code AbstractContainerScreen.imageWidth} and {@code imageHeight} are final
 * and set by its constructor. Create sizes every one of its screens in
 * {@code init}; the JVM refuses that write with an {@code IllegalAccessError},
 * the screen's {@code init} was skipped, and every Create menu opened at the
 * corner of the window with nothing of its own drawn.
 *
 * <p>So a write from a translated mod's class to an instance field the game
 * declares final is made through {@link studios.milkdromeda.octo.bridge.FinalFields},
 * which sets it as the mod meant.
 */
public final class FinalFieldWrites implements Transformer {
    private static final OctoLog LOG = OctoLog.of(FinalFieldWrites.class);
    private static final String HELPER = "studios/milkdromeda/octo/bridge/FinalFields";

    private final MemberIndex index;

    public FinalFieldWrites(MemberIndex index) {
        this.index = index;
    }

    @Override
    public String name() {
        return "final-field-writes";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() != null && context.mod().isFromAnotherVersion();
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
                MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, next) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String field, String fieldDescriptor) {
                        String declaring = opcode == Opcodes.PUTFIELD ? finalGameField(owner, field, fieldDescriptor)
                                : null;

                        if (declaring == null) {
                            super.visitFieldInsn(opcode, owner, field, fieldDescriptor);
                            return;
                        }

                        box(this, Type.getType(fieldDescriptor));
                        super.visitLdcInsn(declaring + "." + field);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "set",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V", false);
                        changed[0] = true;
                    }
                };
            }
        }, 0);

        if (!changed[0]) {
            return bytes;
        }

        LOG.debug("{} sets fields of the game's that are final now", className.replace('/', '.'));
        return writer.toByteArray();
    }

    /** The game class declaring the field a write reaches, when it is an instance field made final there. */
    private String finalGameField(String owner, String name, String descriptor) {
        String key = name + ":" + descriptor;

        for (String type = owner; type != null; ) {
            MemberIndex.Shape shape = index.shape(type);

            if (shape == null) {
                return null;
            }

            if (shape.fields().contains(key)) {
                Integer access = shape.access().get(key);
                return isGame(type) && access != null && (access & Opcodes.ACC_FINAL) != 0
                        && (access & Opcodes.ACC_STATIC) == 0 ? type : null;
            }

            type = shape.superName();
        }

        return null;
    }

    private static void box(MethodVisitor method, Type type) {
        String boxed = switch (type.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean";
            case Type.BYTE -> "java/lang/Byte";
            case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short";
            case Type.INT -> "java/lang/Integer";
            case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float";
            case Type.DOUBLE -> "java/lang/Double";
            default -> null;
        };

        if (boxed != null) {
            method.visitMethodInsn(Opcodes.INVOKESTATIC, boxed, "valueOf",
                    "(" + type.getDescriptor() + ")L" + boxed + ";", false);
        }
    }

    private static boolean isGame(String type) {
        return type.startsWith("net/minecraft/") || type.startsWith("com/mojang/");
    }
}
