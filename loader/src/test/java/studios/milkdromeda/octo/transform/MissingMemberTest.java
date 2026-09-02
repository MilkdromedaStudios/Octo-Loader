package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.neoforged.fml.loading.LoadingModList;

/**
 * A mod calling a corner of the loader API that Octo has not implemented.
 *
 * <p>The call sites here cannot be written in Java — javac will not compile a
 * call to a method that does not exist — so they are assembled directly, which
 * is exactly the situation at runtime: a mod compiled against the real Forge or
 * Fabric, arriving with call sites Octo has no method for.
 */
class MissingMemberTest {
    private static final String OWNER = "net/neoforged/fml/loading/LoadingModList";

    /** Defines what the transformer produced, so the JVM's own verifier passes judgement. */
    private static final class Defining extends ClassLoader {
        Defining() {
            super(MissingMemberTest.class.getClassLoader());
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }

    private byte[] transform(byte[] bytes, String className) {
        return new MissingMemberTransformer(getClass().getClassLoader())
                .transform(className, bytes, TransformContext.of(null, name -> true));
    }

    private Object run(String className, byte[] bytes, String method) throws Exception {
        Class<?> type = new Defining().define(className.replace('/', '.'), transform(bytes, className));
        Method entry = type.getDeclaredMethod(method);
        entry.setAccessible(true);
        return entry.invoke(null);
    }

    @Test
    @DisplayName("a call to a method Octo does not implement returns a default instead of throwing")
    void aMissingMethodBecomesADefault() throws Exception {
        String className = "com/example/gapmod/ObjectCaller";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe",
                "()Ljava/lang/Object;", null, null);
        probe.visitCode();
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, "get", "()L" + OWNER + ";", false);
        probe.visitLdcInsn("somemod");
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "getSomethingOctoNeverWrote",
                "(Ljava/lang/String;)Ljava/lang/Object;", false);
        probe.visitInsn(Opcodes.ARETURN);
        probe.visitMaxs(0, 0);
        probe.visitEnd();
        writer.visitEnd();

        assertNull(run(className, writer.toByteArray(), "probe"),
                "the missing call should have produced null rather than a NoSuchMethodError");
    }

    @Test
    @DisplayName("operands of every width are discarded, so the stack stays balanced")
    void argumentsOfEveryWidthAreDiscarded() throws Exception {
        String className = "com/example/gapmod/WideCaller";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

        // A long and a double are two stack slots each; getting that wrong is
        // how a rewrite passes ASM and then fails the verifier at class load.
        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe",
                "()Ljava/lang/Object;", null, null);
        probe.visitCode();
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, "get", "()L" + OWNER + ";", false);
        probe.visitLdcInsn("mod");
        probe.visitLdcInsn(7L);
        probe.visitLdcInsn(1.5d);
        probe.visitInsn(Opcodes.ICONST_3);
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "neverWritten", "(Ljava/lang/String;JDI)J", false);
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false);
        probe.visitInsn(Opcodes.ARETURN);
        probe.visitMaxs(0, 0);
        probe.visitEnd();
        writer.visitEnd();

        assertEquals(0L, run(className, writer.toByteArray(), "probe"));
    }

    @Test
    @DisplayName("a field Octo does not have reads as a default")
    void aMissingFieldBecomesADefault() throws Exception {
        String className = "com/example/gapmod/FieldCaller";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe",
                "()Ljava/lang/Object;", null, null);
        probe.visitCode();
        probe.visitFieldInsn(Opcodes.GETSTATIC, OWNER, "NOT_A_FIELD", "I");
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        probe.visitInsn(Opcodes.ARETURN);
        probe.visitMaxs(0, 0);
        probe.visitEnd();
        writer.visitEnd();

        assertEquals(0, run(className, writer.toByteArray(), "probe"));
    }

    @Test
    @DisplayName("a call to a method Octo does implement is left exactly as the mod compiled it")
    void anImplementedCallIsUntouched() {
        String className = "com/example/gapmod/RealCaller";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe", "()Z", null, null);
        probe.visitCode();
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, "get", "()L" + OWNER + ";", false);
        probe.visitLdcInsn("somemod");
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "isLoaded", "(Ljava/lang/String;)Z", false);
        probe.visitInsn(Opcodes.IRETURN);
        probe.visitMaxs(0, 0);
        probe.visitEnd();
        writer.visitEnd();

        byte[] original = writer.toByteArray();

        assertSame(original, transform(original, className),
                "nothing should be rewritten when every call resolves");
    }

    @Test
    @DisplayName("a mod's own missing method is still an error, because that is the mod's bug")
    void aModsOwnMissingMethodIsNotCoveredUp() {
        String className = "com/example/gapmod/SelfCaller";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe",
                "()Ljava/lang/Object;", null, null);
        probe.visitCode();
        // Names the loader API in the constant pool so the cheap prefilter admits
        // the class, but calls something of its own that is not there.
        probe.visitLdcInsn(org.objectweb.asm.Type.getObjectType(OWNER));
        probe.visitInsn(Opcodes.POP);
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/gapmod/Missing", "gone",
                "()Ljava/lang/Object;", false);
        probe.visitInsn(Opcodes.ARETURN);
        probe.visitMaxs(0, 0);
        probe.visitEnd();
        writer.visitEnd();

        byte[] original = writer.toByteArray();

        assertSame(original, transform(original, className),
                "only Octo's own API packages are covered");
        assertThrows(Throwable.class, () -> run(className, original, "probe"),
                "a mod calling into its own missing class must still fail");
    }

    /** A class whose only method calls something on the loader API that is not there. */
    private byte[] callReturning(String className, String returnDescriptor) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe",
                "()Ljava/lang/Object;", null, null);
        probe.visitCode();
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, OWNER, "get", "()L" + OWNER + ";", false);
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OWNER, "neverWritten", "()" + returnDescriptor, false);
        probe.visitInsn(Opcodes.ARETURN);
        probe.visitMaxs(0, 0);
        probe.visitEnd();
        writer.visitEnd();

        return writer.toByteArray();
    }

    @Test
    @DisplayName("a missing call that should have returned a collection returns an empty one, not null")
    void aMissingCollectionIsEmptyRatherThanNull() throws Exception {
        // jei's shape exactly: ModList.getAllScanData() was a gap, the default
        // was null, and jei went straight to .iterator() on it and did not
        // construct. Nothing is an answer; null is a second crash.
        Object list = run("com/example/gapmod/ListCaller", callReturning("com/example/gapmod/ListCaller",
                "Ljava/util/List;"), "probe");

        assertNotNull(list, "a list-returning gap must not hand back null");
        assertTrue(((java.util.List<?>) list).isEmpty());

        @SuppressWarnings("unchecked")
        java.util.List<Object> mutable = (java.util.List<Object>) list;
        mutable.add("something");
        assertEquals(1, mutable.size(),
                "a caller that adds to what it was given should not meet an UnsupportedOperationException");

        assertTrue(((java.util.Set<?>) run("com/example/gapmod/SetCaller",
                callReturning("com/example/gapmod/SetCaller", "Ljava/util/Set;"), "probe")).isEmpty());
        assertTrue(((java.util.Map<?, ?>) run("com/example/gapmod/MapCaller",
                callReturning("com/example/gapmod/MapCaller", "Ljava/util/Map;"), "probe")).isEmpty());
        assertFalse(((java.util.Optional<?>) run("com/example/gapmod/OptionalCaller",
                callReturning("com/example/gapmod/OptionalCaller", "Ljava/util/Optional;"), "probe")).isPresent());
    }

    @Test
    @DisplayName("a missing call that should have returned an array returns an empty one")
    void aMissingArrayIsEmptyRatherThanNull() throws Exception {
        Object strings = run("com/example/gapmod/ArrayCaller",
                callReturning("com/example/gapmod/ArrayCaller", "[Ljava/lang/String;"), "probe");

        assertEquals(0, ((String[]) strings).length);

        Object nested = run("com/example/gapmod/NestedArrayCaller",
                callReturning("com/example/gapmod/NestedArrayCaller", "[[I"), "probe");

        assertEquals(0, ((int[][]) nested).length);

        Object bytes = run("com/example/gapmod/ByteArrayCaller",
                callReturning("com/example/gapmod/ByteArrayCaller", "[B"), "probe");

        assertEquals(0, ((byte[]) bytes).length);
    }

    @Test
    @DisplayName("a missing call to something Octo has no empty value for still returns null")
    void anythingElseIsStillNull() throws Exception {
        assertNull(run("com/example/gapmod/PlainCaller",
                callReturning("com/example/gapmod/PlainCaller", "Ljava/lang/String;"), "probe"),
                "an empty string is not the same claim as no string, so null stands");
    }

    @Test
    @DisplayName("getModFileById answers presence, which is what mods use it for")
    void modFileLookupAnswersPresence() {
        // Nothing is running, so nothing is loaded, and the honest answer to
        // "is create here" is null rather than an exception.
        assertNull(LoadingModList.get().getModFileById("create"));
        assertNotNull(LoadingModList.get().getModFiles());
        assertTrue(LoadingModList.get().getModFiles().isEmpty());
        assertFalse(LoadingModList.get().isLoaded("create"));
    }
}
