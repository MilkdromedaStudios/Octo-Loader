package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Every entity's own tag of mod data, as NeoForge's and Forge's patched game
 * gives it: {@code Entity.getPersistentData()}, saved with the entity under
 * {@code NeoForgeData}.
 *
 * <p>The loaders' entity interface declares the method and leaves it to the
 * patch to implement, so on this game every call to it was an
 * {@code AbstractMethodError}. Create asks it of every entity, every tick,
 * to know whether it is fire-immune.
 */
public final class EntityPersistentData implements Transformer {
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String TAG = "net/minecraft/nbt/CompoundTag";
    private static final String FIELD = "octo$persistentData";
    private static final String HELPER = "studios/milkdromeda/octo/bridge/forge/EntityData";
    private static final String OUTPUT = "(Lnet/minecraft/world/level/storage/ValueOutput;)V";
    private static final String INPUT = "(Lnet/minecraft/world/level/storage/ValueInput;)V";

    @Override
    public String name() {
        return "entity-persistent-data";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(ENTITY);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        ClassReader reader = new ClassReader(bytes);
        boolean[] declared = { false };

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (name.equals("getPersistentData") && descriptor.equals("()L" + TAG + ";")) {
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
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);

                if (name.equals("saveWithoutId") && descriptor.equals(OUTPUT)) {
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, ENTITY, FIELD, "L" + TAG + ";");
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "save",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                        }
                    };
                }

                if (name.equals("load") && descriptor.equals(INPUT)) {
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "load",
                                        "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, TAG);
                                super.visitFieldInsn(Opcodes.PUTFIELD, ENTITY, FIELD, "L" + TAG + ";");
                            }

                            super.visitInsn(opcode);
                        }
                    };
                }

                return delegate;
            }

            @Override
            public void visitEnd() {
                super.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, FIELD, "L" + TAG + ";", null, null)
                        .visitEnd();

                // public CompoundTag getPersistentData(): made the first time it is asked for.
                MethodVisitor method = super.visitMethod(Opcodes.ACC_PUBLIC, "getPersistentData", "()L" + TAG + ";",
                        null, null);
                method.visitCode();
                Label made = new Label();
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(Opcodes.GETFIELD, ENTITY, FIELD, "L" + TAG + ";");
                method.visitJumpInsn(Opcodes.IFNONNULL, made);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitTypeInsn(Opcodes.NEW, TAG);
                method.visitInsn(Opcodes.DUP);
                method.visitMethodInsn(Opcodes.INVOKESPECIAL, TAG, "<init>", "()V", false);
                method.visitFieldInsn(Opcodes.PUTFIELD, ENTITY, FIELD, "L" + TAG + ";");
                method.visitLabel(made);
                method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                method.visitVarInsn(Opcodes.ALOAD, 0);
                method.visitFieldInsn(Opcodes.GETFIELD, ENTITY, FIELD, "L" + TAG + ";");
                method.visitInsn(Opcodes.ARETURN);
                method.visitMaxs(0, 0);
                method.visitEnd();
                super.visitEnd();
            }
        }, 0);

        return writer.toByteArray();
    }
}
