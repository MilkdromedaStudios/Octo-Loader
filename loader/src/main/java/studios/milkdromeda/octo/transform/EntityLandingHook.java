package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Puts back, in the game's {@code Entity.move}, the call each block under a
 * moving entity got when its vertical movement was stopped
 * ({@link studios.milkdromeda.octo.bridge.BlockLandings}).
 *
 * <p>At each return from {@code move} the entity and its
 * {@code verticalCollision} are handed to the bridge, which tells the block at
 * {@code getOnPosLegacy()}, the block the old game told.
 */
public final class EntityLandingHook implements Transformer {
    static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String MOVE = "(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V";
    private static final String BRIDGE = "studios/milkdromeda/octo/bridge/BlockLandings";

    @Override
    public String name() {
        return "entity-landing-hook";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(ENTITY);
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

                if (!name.equals("move") || !descriptor.equals(MOVE)) {
                    return delegate;
                }

                changed[0] = true;
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            // BlockLandings.afterMove(this, this.verticalCollision): no branch here, so
                            // the method's frames stay as the game wrote them.
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, ENTITY, "verticalCollision", "Z");
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "afterMove", "(Ljava/lang/Object;Z)V",
                                    false);
                        }

                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
