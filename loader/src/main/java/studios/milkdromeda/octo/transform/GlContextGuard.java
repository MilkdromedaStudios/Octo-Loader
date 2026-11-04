package studios.milkdromeda.octo.transform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Keeps an older mod's own OpenGL calls from ending the game where no OpenGL
 * context is current.
 *
 * <p>Until 26.x a screen was drawn by issuing OpenGL calls as it went, and
 * mods did so themselves: catnip copies the frame buffer behind its config
 * screen with {@code glBlitFramebuffer}. 26.x collects a screen first and
 * draws it later, and while it collects no context is current. LWJGL answers
 * a call made then by aborting the JVM — no exception, no crash report, the
 * game simply gone — the moment a player opened Create's config.
 *
 * <p>So a translated mod's direct call into the GL bindings or the game's GL
 * state manager goes through a method of its own class that makes it only
 * when a context is current, and otherwise answers as a skipped call does —
 * nothing, zero, false or null — said once in the log.
 */
public final class GlContextGuard implements Transformer {
    private static final OctoLog LOG = OctoLog.of(GlContextGuard.class);
    private static final String REPORT = "studios/milkdromeda/octo/transform/GlContextGuard";
    private static final ByteScan NAMES_GL = new ByteScan(List.of("org/lwjgl/opengl/", "GlStateManager"));
    private static final java.util.Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public String name() {
        return "gl-context-guard";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return context.mod() != null && context.mod().isFromAnotherVersion();
    }

    static boolean isGl(String owner) {
        return owner.startsWith("org/lwjgl/opengl/") || owner.endsWith("/GlStateManager");
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        if (!NAMES_GL.matches(bytes)) {
            return bytes;
        }

        ClassReader reader = new ClassReader(bytes);

        // An interface's default methods call GL too (catnip's stencils); an
        // interface can hold the guard where it may have private methods.
        boolean isInterface = (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0;
        int version = reader.readUnsignedShort(6);

        if (isInterface && version < Opcodes.V9) {
            return bytes;
        }

        // One guarded method per GL method called, keyed by owner, name and descriptor.
        Map<String, String[]> guarded = new LinkedHashMap<>();
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM9, next) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String methodDescriptor,
                            boolean onInterface) {
                        if (opcode != Opcodes.INVOKESTATIC || !isGl(owner)) {
                            super.visitMethodInsn(opcode, owner, method, methodDescriptor, onInterface);
                            return;
                        }

                        String key = owner + "." + method + methodDescriptor;
                        String[] guard = guarded.computeIfAbsent(key, k -> new String[] {
                                "octo$gl$" + guarded.size(), owner, method, methodDescriptor,
                                String.valueOf(onInterface) });
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, className, guard[0], methodDescriptor,
                                isInterface);
                    }
                };
            }

            @Override
            public void visitEnd() {
                for (String[] guard : guarded.values()) {
                    writeGuard(cv, guard[0], guard[1], guard[2], guard[3], Boolean.parseBoolean(guard[4]));
                }

                super.visitEnd();
            }
        }, 0);

        if (guarded.isEmpty()) {
            return bytes;
        }

        LOG.debug("{} makes its OpenGL calls only where a context is current", className.replace('/', '.'));
        return writer.toByteArray();
    }

    private static void writeGuard(ClassVisitor writer, String name, String owner, String method, String descriptor,
            boolean onInterface) {
        MethodVisitor guard = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, name,
                descriptor, null, null);
        guard.visitCode();
        Label skipped = new Label();
        guard.visitMethodInsn(Opcodes.INVOKESTATIC, REPORT, "hasContext", "()Z", false);
        guard.visitJumpInsn(Opcodes.IFEQ, skipped);

        Type[] parameters = Type.getArgumentTypes(descriptor);
        List<Integer> slots = new ArrayList<>();

        for (int i = 0, slot = 0; i < parameters.length; i++) {
            slots.add(slot);
            guard.visitVarInsn(parameters[i].getOpcode(Opcodes.ILOAD), slot);
            slot += parameters[i].getSize();
        }

        guard.visitMethodInsn(Opcodes.INVOKESTATIC, owner, method, descriptor, onInterface);
        Type returned = Type.getReturnType(descriptor);
        guard.visitInsn(returned.getOpcode(Opcodes.IRETURN));

        guard.visitLabel(skipped);
        guard.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
        guard.visitLdcInsn(owner.substring(owner.lastIndexOf('/') + 1) + "." + method);
        guard.visitMethodInsn(Opcodes.INVOKESTATIC, REPORT, "skipped", "(Ljava/lang/String;)V", false);

        switch (returned.getSort()) {
            case Type.VOID -> {
            }
            case Type.LONG -> guard.visitInsn(Opcodes.LCONST_0);
            case Type.FLOAT -> guard.visitInsn(Opcodes.FCONST_0);
            case Type.DOUBLE -> guard.visitInsn(Opcodes.DCONST_0);
            case Type.OBJECT, Type.ARRAY -> guard.visitInsn(Opcodes.ACONST_NULL);
            default -> guard.visitInsn(Opcodes.ICONST_0);
        }

        guard.visitInsn(returned.getOpcode(Opcodes.IRETURN));
        guard.visitMaxs(0, 0);
        guard.visitEnd();
    }

    private static volatile java.lang.invoke.MethodHandle capabilities;

    /**
     * Whether this thread has OpenGL to call: LWJGL's capabilities for it,
     * which it has only where a context was made current on it.
     */
    public static boolean hasContext() {
        try {
            java.lang.invoke.MethodHandle handle = capabilities;

            if (handle == null) {
                Class<?> gl = Class.forName("org.lwjgl.opengl.GL", false,
                        Thread.currentThread().getContextClassLoader());
                handle = java.lang.invoke.MethodHandles.publicLookup().findStatic(gl, "getCapabilities",
                        java.lang.invoke.MethodType.methodType(Class.forName("org.lwjgl.opengl.GLCapabilities", false,
                                gl.getClassLoader())));
                capabilities = handle;
            }

            return handle.invoke() != null;
        } catch (Throwable e) {
            // LWJGL says there is none by throwing.
            return false;
        }
    }

    /** Said once per GL method: a mod drew with OpenGL where 26.x had no context for it. */
    public static void skipped(String call) {
        if (REPORTED.add(call)) {
            LOG.warn("a mod called OpenGL ({}) while no context was current, as 26.x collects screens before "
                    + "drawing them; the call was skipped", call);
        }
    }
}
