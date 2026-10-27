package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A translated mod's callback failing in the way translation makes likely.
 *
 * <p>The callbacks are assembled as javac compiles lambda bodies — synthetic
 * {@code lambda$} methods — and judged by the JVM's own verifier once wrapped.
 */
class CallbackGuardTest {
    private static final String NAME = "mod/Callbacks";
    private static final int LAMBDA = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;

    private static final class Defining extends ClassLoader {
        Defining() {
            super(CallbackGuardTest.class.getClassLoader());
        }

        Class<?> define(byte[] bytes) {
            return defineClass(NAME.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    private static void throwing(MethodVisitor method, String type) {
        method.visitTypeInsn(Opcodes.NEW, type);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false);
        method.visitInsn(Opcodes.ATHROW);
    }

    private static byte[] callbacks() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, NAME, null, "java/lang/Object", null);

        // What a call into a member this version lacks throws.
        MethodVisitor linkage = writer.visitMethod(LAMBDA, "lambda$onTick$0", "()V", null, null);
        linkage.visitCode();
        throwing(linkage, "java/lang/NoSuchMethodError");
        linkage.visitMaxs(0, 0);
        linkage.visitEnd();

        // A default that was null, one call later.
        MethodVisitor counted = writer.visitMethod(LAMBDA, "lambda$count$1", "()I", null, null);
        counted.visitCode();
        throwing(counted, "java/lang/NullPointerException");
        counted.visitMaxs(0, 0);
        counted.visitEnd();

        // A command's syntax error is its contract with the caller.
        MethodVisitor checked = writer.visitMethod(LAMBDA, "lambda$run$2", "()V", null,
                new String[] { "java/io/IOException" });
        checked.visitCode();
        throwing(checked, "java/io/IOException");
        checked.visitMaxs(0, 0);
        checked.visitEnd();

        // A body with a branch and a handler of its own, which must keep working.
        MethodVisitor branchy = writer.visitMethod(LAMBDA, "lambda$pick$3", "(Z)Ljava/lang/String;", null, null);
        branchy.visitCode();
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label caught = new Label();
        Label otherwise = new Label();
        branchy.visitTryCatchBlock(tryStart, tryEnd, caught, "java/lang/IllegalStateException");
        branchy.visitLabel(tryStart);
        branchy.visitVarInsn(Opcodes.ILOAD, 0);
        branchy.visitJumpInsn(Opcodes.IFEQ, otherwise);
        throwing(branchy, "java/lang/IllegalStateException");
        branchy.visitLabel(otherwise);
        branchy.visitLdcInsn("plain");
        branchy.visitLabel(tryEnd);
        branchy.visitInsn(Opcodes.ARETURN);
        branchy.visitLabel(caught);
        branchy.visitInsn(Opcodes.POP);
        branchy.visitLdcInsn("handled by the mod");
        branchy.visitInsn(Opcodes.ARETURN);
        branchy.visitMaxs(0, 0);
        branchy.visitEnd();

        // Not a lambda body, so not a callback.
        MethodVisitor plain = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "plain", "()V", null, null);
        plain.visitCode();
        throwing(plain, "java/lang/NoSuchMethodError");
        plain.visitMaxs(0, 0);
        plain.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Class<?> guarded() {
        return new Defining().define(new CallbackGuard().transform(NAME, callbacks(),
                TransformContext.of(null, name -> true)));
    }

    private static Object call(Class<?> type, String name, Class<?>[] parameters, Object... arguments)
            throws Throwable {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);

        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    @DisplayName("a callback reaching something this version lacks is skipped, not fatal")
    void linkageFailureIsContained() throws Throwable {
        assertNull(call(guarded(), "lambda$onTick$0", new Class<?>[0]));
    }

    @Test
    @DisplayName("a contained callback returns a default of its own type")
    void containedCallbackReturnsDefault() throws Throwable {
        assertEquals(0, call(guarded(), "lambda$count$1", new Class<?>[0]));
    }

    @Test
    @DisplayName("a checked exception the callback declares still reaches its caller")
    void checkedExceptionsPassThrough() {
        Class<?> type = guarded();
        assertThrows(IOException.class, () -> call(type, "lambda$run$2", new Class<?>[0]));
    }

    @Test
    @DisplayName("the mod's own handlers and branches are untouched")
    void ownHandlersStillWin() throws Throwable {
        Class<?> type = guarded();
        assertEquals("handled by the mod", call(type, "lambda$pick$3", new Class<?>[] { boolean.class }, true));
        assertEquals("plain", call(type, "lambda$pick$3", new Class<?>[] { boolean.class }, false));
    }

    @Test
    @DisplayName("only lambda bodies are guarded")
    void ordinaryMethodsAreLeftAlone() {
        Class<?> type = guarded();
        assertThrows(NoSuchMethodError.class, () -> call(type, "plain", new Class<?>[0]));
        assertTrue(CallbackGuard.isCallback(LAMBDA, "lambda$x$0"));
        assertFalse(CallbackGuard.isCallback(Opcodes.ACC_PRIVATE, "lambda$x$0"));
    }

    /** {@code register()} hands out {@code References::onTick} as a Runnable; {@code onTick} throws. */
    private static byte[] references() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/References", null, "java/lang/Object", null);

        MethodVisitor onTick = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onTick", "()V", null,
                null);
        onTick.visitCode();
        throwing(onTick, "java/lang/NullPointerException");
        onTick.visitMaxs(0, 0);
        onTick.visitEnd();

        MethodVisitor register = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register",
                "()Ljava/lang/Runnable;", null, null);
        register.visitCode();
        register.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;",
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                        "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                org.objectweb.asm.Type.getMethodType("()V"),
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "mod/References", "onTick", "()V", false),
                org.objectweb.asm.Type.getMethodType("()V"));
        register.visitInsn(Opcodes.ARETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("a method reference handed out as a callback is guarded, the method itself is not")
    void methodReferencesAreGuarded() throws Throwable {
        byte[] guarded = new CallbackGuard().transform("mod/References", references(),
                TransformContext.of(null, name -> true));
        Class<?> type = new ClassLoader(CallbackGuardTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass("mod.References", guarded, 0, guarded.length);
            }
        }.define();

        Runnable callback = (Runnable) type.getMethod("register").invoke(null);
        callback.run();
        assertThrows(NullPointerException.class, () -> call(type, "onTick", new Class<?>[0]),
                "a direct call still sees what the method throws");
    }

    @Test
    @DisplayName("a method reference inside an interface is bridged with an interface method")
    void interfaceReferencesAreBridged() throws Throwable {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "mod/Attribute", null, "java/lang/Object", null);

        MethodVisitor onTick = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "onTick", "()V", null,
                null);
        onTick.visitCode();
        throwing(onTick, "java/lang/NullPointerException");
        onTick.visitMaxs(0, 0);
        onTick.visitEnd();

        MethodVisitor register = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "register",
                "()Ljava/lang/Runnable;", null, null);
        register.visitCode();
        register.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;",
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                        "metafactory", "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                + "Ljava/lang/invoke/CallSite;", false),
                org.objectweb.asm.Type.getMethodType("()V"),
                new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "mod/Attribute", "onTick", "()V", true),
                org.objectweb.asm.Type.getMethodType("()V"));
        register.visitInsn(Opcodes.ARETURN);
        register.visitMaxs(0, 0);
        register.visitEnd();
        writer.visitEnd();

        byte[] guarded = new CallbackGuard().transform("mod/Attribute", writer.toByteArray(),
                TransformContext.of(null, name -> true));
        Class<?> type = new ClassLoader(CallbackGuardTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass("mod.Attribute", guarded, 0, guarded.length);
            }
        }.define();

        ((Runnable) type.getMethod("register").invoke(null)).run();
    }

    @Test
    @DisplayName("a failing builder lambda hands its builder on rather than null")
    void builderLambdasPassTheirInputOn() throws Throwable {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Components", null, "java/lang/Object", null);
        MethodVisitor lambda = writer.visitMethod(LAMBDA, "lambda$static$4",
                "(Ljava/lang/String;Ljava/lang/StringBuilder;)Ljava/lang/StringBuilder;", null, null);
        lambda.visitCode();
        throwing(lambda, "java/lang/NullPointerException");
        lambda.visitMaxs(0, 0);
        lambda.visitEnd();
        writer.visitEnd();

        byte[] guarded = new CallbackGuard().transform("mod/Components", writer.toByteArray(),
                TransformContext.of(null, name -> true));
        Class<?> type = new ClassLoader(CallbackGuardTest.class.getClassLoader()) {
            Class<?> define() {
                return defineClass("mod.Components", guarded, 0, guarded.length);
            }
        }.define();

        StringBuilder builder = new StringBuilder("components");
        assertEquals(builder, call(type, "lambda$static$4", new Class<?>[] { String.class, StringBuilder.class },
                "captured", builder));
    }
}
