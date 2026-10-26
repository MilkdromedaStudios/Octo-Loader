package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * A translated mod's block whose tick fails: the tick it overrides from the
 * game is contained, and its own methods, and those that answer something,
 * fail as written.
 */
class OverrideGuardTest {
    private static final String WHEEL = "mod/Wheel";
    private static final String BLOCK = "net/minecraft/octotest/Block3";

    private static final class Defining extends ClassLoader {
        Defining() {
            super(OverrideGuardTest.class.getClassLoader());
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }

    private static byte[] classBytes(String internalName) {
        try (InputStream in = OverrideGuardTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static void throwing(ClassWriter writer, String name, String descriptor) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "java/lang/NullPointerException");
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("vec is null");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/NullPointerException", "<init>",
                "(Ljava/lang/String;)V", false);
        method.visitInsn(Opcodes.ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    /** {@code class Wheel extends Block3}, every method of which throws. */
    private static byte[] wheel() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, WHEEL, null, BLOCK, null);
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, BLOCK, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();
        throwing(writer, "tick", "(Ljava/lang/String;)V");
        throwing(writer, "shape", "()Ljava/lang/String;");
        throwing(writer, "spin", "()V");
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void invoke(Object target, String name, Class<?>... parameters) throws Throwable {
        Method method = target.getClass().getMethod(name, parameters);

        try {
            method.invoke(target, new Object[parameters.length]);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    @DisplayName("an overridden game callback that returns nothing is contained; nothing else is")
    void onlyVoidGameOverridesAreGuarded() throws Exception {
        byte[] guarded = new OverrideGuard(new MemberIndex(OverrideGuardTest::classBytes))
                .transform(WHEEL, wheel(), TransformContext.of(null, name -> true));
        Object wheel = new Defining().define(WHEEL.replace('/', '.'), guarded).getConstructor().newInstance();

        assertDoesNotThrow(() -> invoke(wheel, "tick", String.class), "the game's tick should just return");
        assertThrows(NullPointerException.class, () -> invoke(wheel, "shape"),
                "a callback that answers something has no default to give");
        assertThrows(NullPointerException.class, () -> invoke(wheel, "spin"), "the mod's own method is its own");
    }
}
