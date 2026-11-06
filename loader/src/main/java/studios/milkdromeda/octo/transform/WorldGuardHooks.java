package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Where the world guard ({@link studios.milkdromeda.octo.bridge.WorldGuard})
 * sits in the game: as the server opens its world, before any level is made;
 * as it stops, once the world is saved;
 * after each chunk of blocks or entities is read from its region file; and
 * before each is written back, which a chunk holding content the game does not
 * know is not.
 */
public final class WorldGuardHooks implements Transformer {
    private static final String SERVER = "net/minecraft/server/MinecraftServer";
    private static final String STORAGE = "net/minecraft/world/level/chunk/storage/SimpleRegionStorage";
    private static final String GUARD = "studios/milkdromeda/octo/bridge/WorldGuard";
    private static final String POS = "Lnet/minecraft/world/level/ChunkPos;";

    @Override
    public String name() {
        return "world-guard";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return className.equals(SERVER) || className.equals(STORAGE);
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

                if (className.equals(SERVER) && name.equals("loadLevel") && descriptor.equals("()V")) {
                    changed[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "opening", "(Ljava/lang/Object;)V",
                                    false);
                        }
                    };
                }

                if (className.equals(SERVER) && name.equals("stopServer") && descriptor.equals("()V")) {
                    changed[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                // The world saved and closed: the session ended cleanly.
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "closed", "(Ljava/lang/Object;)V",
                                        false);
                            }

                            super.visitInsn(opcode);
                        }
                    };
                }

                if (className.equals(STORAGE) && name.equals("read")
                        && descriptor.equals("(" + POS + ")Ljava/util/concurrent/CompletableFuture;")) {
                    changed[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.ARETURN) {
                                // The future read, handed over to be looked at as it completes.
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "afterRead",
                                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                        false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, "java/util/concurrent/CompletableFuture");
                            }

                            super.visitInsn(opcode);
                        }
                    };
                }

                if (className.equals(STORAGE) && name.equals("write") && descriptor.startsWith("(" + POS)
                        && descriptor.endsWith(")Ljava/util/concurrent/CompletableFuture;")) {
                    changed[0] = true;
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            Label write = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, GUARD, "keepOnDisk",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                            super.visitJumpInsn(Opcodes.IFEQ, write);
                            // Kept as it is on disk: "written", and nothing written.
                            super.visitInsn(Opcodes.ACONST_NULL);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/util/concurrent/CompletableFuture",
                                    "completedFuture", "(Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;",
                                    false);
                            super.visitInsn(Opcodes.ARETURN);
                            super.visitLabel(write);
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
