package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * What a class Octo stood in for answers when a mod calls it.
 *
 * <p>The player's log had four of these standing in for NeoForge classes on a
 * launch where jei then failed on a null it had been handed. Which null it was
 * came from elsewhere, but the shape is the same wherever it comes from: a mod
 * asking a loader for a list of something is about to walk it.
 */
class PhantomDefaultsTest {
    private static final String ABSENT = "net/neoforged/neoforge/common/NeoForge";

    private static final class Defining extends ClassLoader {
        Defining() {
            super(PhantomDefaultsTest.class.getClassLoader());
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }

    /** A mod class calling a static method on a class that is not here. */
    private static byte[] callerOf(String name, String descriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "com/example/mod/Caller", null, "java/lang/Object", null);

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe", "()V",
                null, null);
        probe.visitCode();
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, ABSENT, name, descriptor, false);
        probe.visitInsn(Type.getReturnType(descriptor).getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
        probe.visitInsn(Opcodes.RETURN);
        probe.visitMaxs(0, 0);
        probe.visitEnd();
        writer.visitEnd();

        return writer.toByteArray();
    }

    private static Object answerOf(String name, String descriptor) throws Exception {
        PhantomClasses phantoms = new PhantomClasses();
        phantoms.observe(callerOf(name, descriptor), candidate -> !ABSENT.equals(candidate));

        Class<?> standIn = new Defining().define(ABSENT.replace('/', '.'), phantoms.generate(ABSENT));

        return standIn.getDeclaredMethod(name).invoke(null);
    }

    @Test
    @DisplayName("a stand-in asked for a list hands back an empty one, not null")
    void collectionsAreEmptyRatherThanNull() throws Exception {
        Object plugins = answerOf("plugins", "()Ljava/util/List;");

        assertNotNull(plugins, "a mod is about to iterate this");
        assertTrue(((List<?>) plugins).isEmpty());

        assertFalse(((Optional<?>) answerOf("registry", "()Ljava/util/Optional;")).isPresent());
        assertEquals(0, ((String[]) answerOf("names", "()[Ljava/lang/String;")).length);
    }

    @Test
    @DisplayName("everything else is still null, and the numbers are still zero")
    void theRestIsUnchanged() throws Exception {
        assertNull(answerOf("name", "()Ljava/lang/String;"),
                "an empty string is not the same claim as no string");
        assertEquals(0, answerOf("count", "()I"));
        assertEquals(0L, answerOf("seed", "()J"));
    }
}
