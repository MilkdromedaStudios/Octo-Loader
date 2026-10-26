package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.DataResult;

/**
 * Codec code from before 1.20.5 calling DataFixerUpper methods that are gone,
 * answered by the shims rather than a default.
 */
class ShimsTest {
    private static final String CALLER = "mod/CodecUser";
    private static final String RESULT = "com/mojang/serialization/DataResult";

    private static final class Defining extends ClassLoader {
        Defining() {
            super(ShimsTest.class.getClassLoader());
        }

        Class<?> define(byte[] bytes) {
            return defineClass(CALLER.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    private static byte[] classBytes(String internalName) {
        try (InputStream in = ShimsTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** Calls compiled against DataFixerUpper 6, when DataResult was a class. */
    private static Class<?> caller() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);

        MethodVisitor get = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "get",
                "(L" + RESULT + ";)Ljava/lang/Object;", null, null);
        get.visitCode();
        get.visitVarInsn(Opcodes.ALOAD, 0);
        get.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "get", "()Lcom/mojang/datafixers/util/Either;", false);
        get.visitInsn(Opcodes.ARETURN);
        get.visitMaxs(0, 0);
        get.visitEnd();

        MethodVisitor orThrow = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "orThrow",
                "(L" + RESULT + ";Ljava/util/function/Consumer;)Ljava/lang/Object;", null, null);
        orThrow.visitCode();
        orThrow.visitVarInsn(Opcodes.ALOAD, 0);
        orThrow.visitInsn(Opcodes.ICONST_0);
        orThrow.visitVarInsn(Opcodes.ALOAD, 1);
        orThrow.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "getOrThrow",
                "(ZLjava/util/function/Consumer;)Ljava/lang/Object;", false);
        orThrow.visitInsn(Opcodes.ARETURN);
        orThrow.visitMaxs(0, 0);
        orThrow.visitEnd();

        MethodVisitor error = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "error",
                "()Ljava/lang/Object;", null, null);
        error.visitCode();
        error.visitLdcInsn("broken");
        error.visitMethodInsn(Opcodes.INVOKESTATIC, RESULT, "error", "(Ljava/lang/String;)L" + RESULT + ";", false);
        error.visitInsn(Opcodes.ARETURN);
        error.visitMaxs(0, 0);
        error.visitEnd();

        writer.visitEnd();
        MissingMemberTransformer gaps = MissingMemberTransformer.forTranslatedMods(
                new MemberIndex(ShimsTest::classBytes), List.of("mod/"));
        return new Defining().define(gaps.transform(CALLER, writer.toByteArray(),
                TransformContext.of(null, name -> true)));
    }

    private static Object call(Class<?> type, String name, Object... arguments) throws Throwable {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                try {
                    return method.invoke(null, arguments);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        }

        throw new AssertionError(name);
    }

    @Test
    @DisplayName("DataResult.get() hands back the value on the left, or the error on the right")
    void getIsAnEither() throws Throwable {
        Class<?> type = caller();

        Either<?, ?> success = (Either<?, ?>) call(type, "get", DataResult.success("value"));
        assertEquals("value", success.left().orElseThrow());

        Either<?, ?> failure = (Either<?, ?>) call(type, "get", DataResult.error(() -> "nope"));
        assertTrue(failure.left().isEmpty());
        assertEquals("nope", ((DataResult.Error<?>) failure.right().orElseThrow()).message());
    }

    @Test
    @DisplayName("getOrThrow(false, onError) reports the error, then throws")
    void getOrThrowReportsThenThrows() throws Throwable {
        Class<?> type = caller();
        List<String> reported = new ArrayList<>();
        Consumer<String> onError = reported::add;

        assertEquals("value", call(type, "orThrow", DataResult.success("value"), onError));
        assertThrows(RuntimeException.class, () -> call(type, "orThrow", DataResult.error(() -> "nope"), onError));
        assertEquals(List.of("nope"), reported);
    }

    @Test
    @DisplayName("DataResult.error(String) becomes error(Supplier)")
    void errorTakesAString() throws Throwable {
        DataResult<?> result = (DataResult<?>) call(caller(), "error");

        assertEquals("broken", result.error().orElseThrow().message());
    }
}
