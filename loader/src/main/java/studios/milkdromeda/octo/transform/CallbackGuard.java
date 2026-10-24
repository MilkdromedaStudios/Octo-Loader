package studios.milkdromeda.octo.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Keeps one failing callback in a mod from another version from taking the
 * game down with it.
 *
 * <p>A mod's work after start-up happens in the callbacks it hands the game and
 * the loader APIs: an event listener, a tick hook, a command. Those run inside
 * the game's own loop, so whatever escapes one escapes into the server tick and
 * ends the server — Jade's {@code SERVER_STARTED} listener reaching a class
 * that would not initialise was a crash report, not one feature missing.
 * Translation makes that kind of failure expected rather than exceptional: a
 * default where a member was bypassed is null one call later.
 *
 * <p>So the lambda bodies of translated mods — which is what nearly every
 * callback is compiled to — are wrapped: what a translation can cause is
 * logged once and the callback returns a default; a checked exception the
 * callback declares, and anything the JVM itself is failing with, goes on as
 * written ({@link GapBridge#contain}). {@code -Docto.strict=true} turns this
 * off.
 */
public final class CallbackGuard implements Transformer {
    @Override
    public String name() {
        return "callback-guard";
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
        boolean[] isInterface = { false };
        int[] version = { 0 };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visit(int classVersion, int access, String name, String signature, String superName,
                    String[] interfaces) {
                version[0] = classVersion & 0xFFFF;
                isInterface[0] = (access & Opcodes.ACC_INTERFACE) != 0;
                super.visit(classVersion, access, name, signature, superName, interfaces);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);

                if (delegate == null || !isCallback(access, name) || version[0] < Opcodes.V1_8) {
                    return delegate;
                }

                changed[0] = true;
                return new Guarded(delegate, descriptor, className.replace('/', '.') + "." + name);
            }
        }, 0);

        return changed[0] ? writer.toByteArray() : bytes;
    }

    /** A lambda body: synthetic, named by javac, with code. */
    static boolean isCallback(int access, String name) {
        return name.startsWith("lambda$") && (access & Opcodes.ACC_SYNTHETIC) != 0
                && (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0;
    }

    /** Wraps the whole body in a handler that asks {@link GapBridge} whether to stop there. */
    private static final class Guarded extends MethodVisitor {
        private final Type returnType;
        private final String site;
        private final Label start = new Label();
        private final Label end = new Label();
        private final Label handler = new Label();

        Guarded(MethodVisitor delegate, String descriptor, String site) {
            super(Opcodes.ASM9, delegate);
            this.returnType = Type.getReturnType(descriptor);
            this.site = site;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitLabel(start);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // Registered last, so every handler the method already had is asked
            // first: only what would have left the method reaches this one.
            super.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
            super.visitLabel(end);
            super.visitLabel(handler);
            // Nothing about the locals is needed here, so none are declared,
            // which is compatible with every frame in the body.
            super.visitFrame(Opcodes.F_FULL, 0, new Object[0], 1, new Object[] { "java/lang/Throwable" });
            super.visitLdcInsn(site);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, "studios/milkdromeda/octo/transform/GapBridge", "contain",
                    "(Ljava/lang/Throwable;Ljava/lang/String;)V", false);
            Nothing.push(mv, returnType);
            super.visitInsn(returnType.getOpcode(Opcodes.IRETURN));
            super.visitMaxs(maxStack, maxLocals);
        }
    }
}
