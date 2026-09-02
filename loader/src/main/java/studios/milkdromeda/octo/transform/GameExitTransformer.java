package studios.milkdromeda.octo.transform;

import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Sends the game's exit through Octo before the JVM takes it.
 *
 * <p>The one moment a player most needs the report window is the moment
 * Minecraft calls {@code System.exit(-1)} on a crash, and that is also the
 * moment the window cannot be opened or kept: everything after that call runs in
 * a JVM that is shutting down, where AWT's own shutdown hook is racing yours and
 * wins. Doing the work a few instructions earlier — in the game's thread, before
 * the exit rather than after it — makes all of that ordinary again.
 *
 * <p>Only the game is rewritten. A mod calling {@code System.exit} means it, and
 * a loader that quietly holds the process open on top of it would be a worse
 * surprise than the exit.
 *
 * @see studios.milkdromeda.octo.hook.GameExit
 */
public final class GameExitTransformer implements Transformer {
    private static final OctoLog LOG = OctoLog.of(GameExitTransformer.class);

    private static final String HOOK = "studios/milkdromeda/octo/hook/GameExit";

    /** Whose exits these are. */
    private static final List<String> GAME_PACKAGES = List.of("net/minecraft/", "com/mojang/");

    /** Almost no class mentions either of these, and this says so without parsing. */
    private final ByteScan mentionsAnExit = new ByteScan(List.of("java/lang/System", "java/lang/Runtime"));

    @Override
    public String name() {
        return "game-exit";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        for (String prefix : GAME_PACKAGES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        if (!handles(className, context) || !mentionsAnExit.matches(bytes)) {
            return bytes;
        }

        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        Rerouter rerouter = new Rerouter(writer);
        reader.accept(rerouter, 0);

        if (!rerouter.changed) {
            return bytes;
        }

        LOG.debug("{} exits the JVM; its exit now goes through Octo first", className.replace('/', '.'));
        return writer.toByteArray();
    }

    private static final class Rerouter extends ClassVisitor {
        boolean changed;

        Rerouter(ClassVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);

            return delegate == null ? null : new MethodVisitor(Opcodes.ASM9, delegate) {
                @Override
                public void visitMethodInsn(int opcode, String owner, String target, String descriptor,
                        boolean isInterface) {
                    if (opcode == Opcodes.INVOKESTATIC && "java/lang/System".equals(owner)
                            && "exit".equals(target) && "(I)V".equals(descriptor)) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "exit", "(I)V", false);
                        changed = true;
                        return;
                    }

                    if (opcode == Opcodes.INVOKEVIRTUAL && "java/lang/Runtime".equals(owner)
                            && ("exit".equals(target) || "halt".equals(target)) && "(I)V".equals(descriptor)) {
                        // The receiver is under the status on the stack, and both
                        // are one slot, so a swap lifts it clear to be dropped.
                        super.visitInsn(Opcodes.SWAP);
                        super.visitInsn(Opcodes.POP);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK,
                                "halt".equals(target) ? "halt" : "exit", "(I)V", false);
                        changed = true;
                        return;
                    }

                    super.visitMethodInsn(opcode, owner, target, descriptor, isInterface);
                }
            };
        }
    }
}
