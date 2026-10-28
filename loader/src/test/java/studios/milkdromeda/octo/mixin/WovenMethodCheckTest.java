package studios.milkdromeda.octo.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** A mixin method the JVM would refuse, emptied rather than allowed to take its class with it. */
class WovenMethodCheckTest {
    private static final String TARGET = "woven/ChunkGenerator";

    private static final class Defining extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(TARGET.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    private static byte[] target(boolean woven) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, TARGET, null, "java/lang/Object", null);

        MethodVisitor own = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "spawns", "()I", null, null);
        own.visitCode();
        own.visitIntInsn(Opcodes.BIPUSH, 7);
        own.visitInsn(Opcodes.IRETURN);
        own.visitMaxs(0, 0);
        own.visitEnd();

        if (woven) {
            // A handler merged without the two captured locals its body still reads.
            MethodVisitor handler = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "handler$zca000$ml$overrides", "(Ljava/lang/Object;)Ljava/lang/String;", null, null);
            handler.visitCode();
            handler.visitVarInsn(Opcodes.ALOAD, 3);
            handler.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/String");
            handler.visitInsn(Opcodes.ARETURN);
            handler.visitMaxs(1, 4);
            handler.visitEnd();
        }

        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("an added method that would not verify keeps its signature and loses its body")
    void unverifiableHandlersAreEmptied() throws Exception {
        byte[] before = target(false);
        byte[] checked = WovenMethodCheck.check(TARGET, before, target(true));
        Class<?> type = new Defining().define(checked);

        Method handler = type.getDeclaredMethod("handler$zca000$ml$overrides", Object.class);
        handler.setAccessible(true);

        assertEquals(null, handler.invoke(null, "x"), "the handler answers a default");
        assertEquals(7, type.getMethod("spawns").invoke(null), "and the class it was merged into works");
    }

    @Test
    @DisplayName("a weave that verifies comes back untouched")
    void soundWeavesAreLeftAlone() {
        byte[] before = target(false);
        byte[] after = target(false).clone();
        assertSame(after, WovenMethodCheck.check(TARGET, before, after));
    }
}
