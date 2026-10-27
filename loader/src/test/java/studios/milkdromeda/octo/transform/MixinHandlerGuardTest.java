package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A translated mod's mixin handler failing inside the game's own method.
 *
 * <p>The handlers are assembled as a mixin class compiles, annotations and
 * all, guarded, and then run by the JVM after its verifier has judged them.
 */
class MixinHandlerGuardTest {
    private static final String NAME = "mod/mixin/TickMixin";
    private static final String INJECTION = "Lorg/spongepowered/asm/mixin/injection/";

    private static final class Defining extends ClassLoader {
        Defining() {
            super(MixinHandlerGuardTest.class.getClassLoader());
        }

        Class<?> define(byte[] bytes) {
            return defineClass(NAME.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    private static void handler(ClassWriter writer, int access, String name, String descriptor, String annotation,
            boolean modifiesFirst) {
        MethodVisitor method = writer.visitMethod(access, name, descriptor, null, null);
        method.visitAnnotation(annotation, false).visitEnd();
        method.visitCode();

        // Changes the value it was given before failing, the way a handler
        // half-way through its work does.
        if (modifiesFirst) {
            method.visitInsn(Opcodes.FCONST_2);
            method.visitVarInsn(Opcodes.FSTORE, 1);
        }

        method.visitTypeInsn(Opcodes.NEW, "java/lang/NullPointerException");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/NullPointerException", "<init>", "()V", false);
        method.visitInsn(Opcodes.ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    private static byte[] mixin(boolean isMixin) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, NAME, null, "java/lang/Object", null);

        if (isMixin) {
            writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false).visitEnd();
        }

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        int instance = Opcodes.ACC_PRIVATE;
        int shared = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;
        handler(writer, instance, "onTickStart", "(Ljava/lang/Object;)V", INJECTION + "Inject;", false);
        handler(writer, instance, "scale", "(F)F", INJECTION + "ModifyArg;", true);
        handler(writer, shared, "delay", "(J)J", INJECTION + "ModifyVariable;", false);
        handler(writer, instance, "shouldPlay", "(Ljava/lang/Object;)Z",
                "Lcom/llamalad7/mixinextras/injector/v2/WrapWithCondition;", false);
        handler(writer, instance, "replaced", "(Ljava/lang/Object;)Ljava/lang/Object;", INJECTION + "Redirect;",
                false);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Class<?> guarded(boolean isMixin) {
        return new Defining().define(new CallbackGuard().transform(NAME, mixin(isMixin),
                TransformContext.of(null, name -> true)));
    }

    private static Object call(Class<?> type, String name, Class<?>[] parameters, Object... arguments)
            throws Throwable {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);

        try {
            return method.invoke(java.lang.reflect.Modifier.isStatic(method.getModifiers()) ? null
                    : type.getConstructor().newInstance(), arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    @DisplayName("an @Inject handler that throws just returns, as if the mod had no hook there")
    void injectReturns() throws Throwable {
        assertNull(call(guarded(true), "onTickStart", new Class<?>[] { Object.class }, "tick"));
    }

    @Test
    @DisplayName("a @Modify handler that throws hands back the value it was asked to change")
    void modifierPassesThrough() throws Throwable {
        Class<?> type = guarded(true);

        assertEquals(2.0f, call(type, "scale", new Class<?>[] { float.class }, 0.5f),
                "what is in the argument's slot when it failed comes back");
        assertEquals(40L, call(type, "delay", new Class<?>[] { long.class }, 40L));
    }

    @Test
    @DisplayName("a condition that throws lets the call it guards go ahead")
    void conditionAllows() throws Throwable {
        assertEquals(true, call(guarded(true), "shouldPlay", new Class<?>[] { Object.class }, "sound"));
    }

    @Test
    @DisplayName("a @Redirect has no answer of its own, and outside a mixin nothing is a handler")
    void othersAreLeftAlone() {
        Class<?> mixin = guarded(true);
        assertThrows(NullPointerException.class,
                () -> call(mixin, "replaced", new Class<?>[] { Object.class }, "value"));

        Class<?> plain = guarded(false);
        assertThrows(NullPointerException.class,
                () -> call(plain, "onTickStart", new Class<?>[] { Object.class }, "tick"));
        assertTrue(CallbackGuard.handlers(new org.objectweb.asm.ClassReader(mixin(false))).isEmpty());
    }
}
