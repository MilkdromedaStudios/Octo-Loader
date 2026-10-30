package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Where the game writes, reads and receives custom payloads, a turn to the
 * mods' own packets ({@link studios.milkdromeda.octo.bridge.forge.ModPayloads}).
 *
 * <p>{@code CustomPacketPayload}'s codec asks the mods for a codec before it
 * settles for the one for payloads it does not know; the client's and the
 * server's packet listeners hand a mod's payload to its handler before the
 * game sees it.
 */
public final class PayloadHooks implements Transformer {
    private static final String HELPER = "studios/milkdromeda/octo/bridge/forge/ModPayloads";
    private static final String CODEC = "net/minecraft/network/protocol/common/custom/CustomPacketPayload$1";
    private static final String CLIENT = "net/minecraft/client/multiplayer/ClientCommonPacketListenerImpl";
    private static final String SERVER = "net/minecraft/server/network/ServerCommonPacketListenerImpl";

    @Override
    public String name() {
        return "payload-hooks";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(CODEC) || className.equals(CLIENT) || className.equals(SERVER);
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

                if (delegate == null) {
                    return delegate;
                }

                if (className.equals(CODEC) && name.equals("findCodec")
                        && descriptor.equals("(Lnet/minecraft/resources/Identifier;)Lnet/minecraft/network/codec/StreamCodec;")) {
                    changed[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            Label game = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, CODEC, "val$idToType", "Ljava/util/Map;");
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "codec",
                                    "(Ljava/util/Map;Ljava/lang/Object;)Ljava/lang/Object;", false);
                            super.visitInsn(Opcodes.DUP);
                            super.visitJumpInsn(Opcodes.IFNULL, game);
                            super.visitTypeInsn(Opcodes.CHECKCAST, "net/minecraft/network/codec/StreamCodec");
                            super.visitInsn(Opcodes.ARETURN);
                            super.visitLabel(game);
                            super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] { "java/lang/Object" });
                            super.visitInsn(Opcodes.POP);
                        }
                    };
                }

                boolean client = className.equals(CLIENT);

                if ((client || className.equals(SERVER)) && name.equals("handleCustomPayload")
                        && descriptor.equals("(Lnet/minecraft/network/protocol/common/"
                                + (client ? "Clientbound" : "Serverbound") + "CustomPayloadPacket;)V")) {
                    changed[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            Label game = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitInsn(client ? Opcodes.ICONST_1 : Opcodes.ICONST_0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "handle",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Z)Z", false);
                            super.visitJumpInsn(Opcodes.IFEQ, game);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(game);
                            super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                        }
                    };
                }

                return delegate;
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }
}
