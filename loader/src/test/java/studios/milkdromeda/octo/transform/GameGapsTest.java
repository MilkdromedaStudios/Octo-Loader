package studios.milkdromeda.octo.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.minecraft.octotest.Box3;
import net.minecraft.octotest.Point3;
import net.minecraft.octotest.Tag3;

/**
 * A mod built for another Minecraft calling into a game that changed shape
 * under it.
 *
 * <p>{@code net.minecraft.octotest} stands in for the game: {@code Box3} takes
 * {@code Shape3} where older code passed {@code Point3}, the way 26.x's
 * {@code Vec3} takes {@code Vector3fc} where 1.21 mods passed
 * {@code Vector3f}. The callers are assembled with the old descriptors, exactly
 * as such a mod arrives, and the JVM's verifier judges what comes out.
 */
class GameGapsTest {
    private static final String CALLER = "mod/Translated";
    private static final String BOX = "net/minecraft/octotest/Box3";
    private static final String POINT = "net/minecraft/octotest/Point3";
    private static final String TAG = "net/minecraft/octotest/Tag3";
    private static final String REGISTRY = "net/minecraft/octotest/Registry3";

    private static final class Defining extends ClassLoader {
        Defining() {
            super(GameGapsTest.class.getClassLoader());
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }

    private static byte[] classBytes(String internalName) {
        try (InputStream in = GameGapsTest.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    private static Class<?> repaired(byte[] caller) {
        MissingMemberTransformer gaps = MissingMemberTransformer.forTranslatedMods(
                new MemberIndex(GameGapsTest::classBytes), List.of("mod/"));
        byte[] bytes = gaps.transform(CALLER, caller, TransformContext.of(null, name -> true));
        return new Defining().define(CALLER.replace('/', '.'), bytes);
    }

    private static Object call(Class<?> type, String method) throws Exception {
        Method entry = type.getDeclaredMethod(method);
        entry.setAccessible(true);
        return entry.invoke(null);
    }

    private static MethodVisitor method(ClassWriter writer, String name, String descriptor) {
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, descriptor, null,
                null);
        method.visitCode();
        return method;
    }

    private static void end(MethodVisitor method) {
        method.visitMaxs(0, 0);
        method.visitEnd();
    }

    /** {@code new Box3(new Point3(2))}, compiled when Box3 took a Point3. */
    private static void widenedConstructor(ClassWriter writer) {
        MethodVisitor method = method(writer, "widened", "()Ljava/lang/Object;");
        method.visitTypeInsn(Opcodes.NEW, BOX);
        method.visitInsn(Opcodes.DUP);
        method.visitTypeInsn(Opcodes.NEW, POINT);
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn(2.0d);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, POINT, "<init>", "(D)V", false);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, BOX, "<init>", "(L" + POINT + ";)V", false);
        method.visitInsn(Opcodes.ARETURN);
        end(method);
    }

    /** {@code new Box3("gone")}: a constructor with nothing like it left. */
    private static void goneConstructor(ClassWriter writer) {
        MethodVisitor method = method(writer, "rebuilt", "()Ljava/lang/Object;");
        method.visitTypeInsn(Opcodes.NEW, BOX);
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("gone");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, BOX, "<init>", "(Ljava/lang/String;)V", false);
        method.visitInsn(Opcodes.ARETURN);
        end(method);

        // The same, for its side effects only: no DUP, nothing kept.
        MethodVisitor discarded = method(writer, "discarded", "()Ljava/lang/Object;");
        discarded.visitTypeInsn(Opcodes.NEW, BOX);
        discarded.visitLdcInsn("gone");
        discarded.visitMethodInsn(Opcodes.INVOKESPECIAL, BOX, "<init>", "(Ljava/lang/String;)V", false);
        discarded.visitLdcInsn("survived");
        discarded.visitInsn(Opcodes.ARETURN);
        end(discarded);
    }

    /** {@code new Box3(1, 2).grow(new Point3(4))}, compiled when grow took and returned Point3. */
    private static void widenedMethod(ClassWriter writer) {
        MethodVisitor method = method(writer, "grown", "()Ljava/lang/Object;");
        method.visitTypeInsn(Opcodes.NEW, BOX);
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn(1.0d);
        method.visitLdcInsn(2.0d);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, BOX, "<init>", "(DD)V", false);
        method.visitTypeInsn(Opcodes.NEW, POINT);
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn(4.0d);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, POINT, "<init>", "(D)V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BOX, "grow", "(L" + POINT + ";)L" + POINT + ";", false);
        method.visitInsn(Opcodes.ARETURN);
        end(method);
    }

    private static final Handle METAFACTORY = new Handle(Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/LambdaMetafactory", "metafactory",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                    + "Ljava/lang/invoke/CallSite;", false);

    /** {@code ((Function<A, Box3>) Box3::new).apply(argument)}, for a constructor taking {@code A}. */
    private static void constructorReference(ClassWriter writer, String name, String parameter,
            Runnable pushArgument, MethodVisitor[] holder) {
        MethodVisitor method = method(writer, name, "()Ljava/lang/Object;");
        holder[0] = method;
        method.visitInvokeDynamicInsn("apply", "()Ljava/util/function/Function;", METAFACTORY,
                Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
                new Handle(Opcodes.H_NEWINVOKESPECIAL, BOX, "<init>", "(" + parameter + ")V", false),
                Type.getType("(" + parameter + ")L" + BOX + ";"));
        pushArgument.run();
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/function/Function", "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true);
        method.visitInsn(Opcodes.ARETURN);
        end(method);
    }

    private static void constructorReferences(ClassWriter writer) {
        MethodVisitor[] widened = new MethodVisitor[1];
        constructorReference(writer, "referenced", "L" + POINT + ";", () -> {
            widened[0].visitTypeInsn(Opcodes.NEW, POINT);
            widened[0].visitInsn(Opcodes.DUP);
            widened[0].visitLdcInsn(3.0d);
            widened[0].visitMethodInsn(Opcodes.INVOKESPECIAL, POINT, "<init>", "(D)V", false);
        }, widened);

        MethodVisitor[] gone = new MethodVisitor[1];
        constructorReference(writer, "referencedGone", "Ljava/lang/String;", () -> gone[0].visitLdcInsn("gone"),
                gone);
    }

    /** {@code tag.<getter>(key[, typeId])}, compiled against the getters as they were before 1.21.5. */
    private static void oldGetter(ClassWriter writer, String method, String getter, String descriptor,
            Integer typeId) {
        MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, method,
                "(L" + TAG + ";Ljava/lang/String;)Ljava/lang/Object;", null, null);
        visitor.visitCode();
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitVarInsn(Opcodes.ALOAD, 1);

        if (typeId != null) {
            visitor.visitIntInsn(Opcodes.BIPUSH, typeId);
        }

        visitor.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TAG, getter, descriptor, false);
        Type returned = Type.getReturnType(descriptor);

        if (returned.getSort() == Type.INT) {
            visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;",
                    false);
        } else if (returned.getSort() == Type.BOOLEAN) {
            visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;",
                    false);
        }

        visitor.visitInsn(Opcodes.ARETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private static void oldGetters(ClassWriter writer) {
        oldGetter(writer, "readInt", "getInt", "(Ljava/lang/String;)I", null);
        oldGetter(writer, "readString", "getString", "(Ljava/lang/String;)Ljava/lang/String;", null);
        oldGetter(writer, "readCompound", "getCompound", "(Ljava/lang/String;)L" + TAG + ";", null);
        oldGetter(writer, "readList", "getList", "(Ljava/lang/String;I)L" + TAG + ";", 10);
        oldGetter(writer, "has", "contains", "(Ljava/lang/String;I)Z", 3);
    }

    private static Object read(String method, Tag3 tag, String key) throws Exception {
        Method entry = repaired(caller()).getDeclaredMethod(method, Tag3.class, String.class);
        entry.setAccessible(true);
        return entry.invoke(null, tag, key);
    }

    /** {@code Registry3.lookup("ab", 3)} called statically, and {@code registry.describe("k", 7L)} on an instance. */
    private static void movedAcrossStatic(ClassWriter writer) {
        MethodVisitor statically = method(writer, "lookedUp", "()Ljava/lang/Object;");
        statically.visitLdcInsn("ab");
        statically.visitInsn(Opcodes.ICONST_3);
        statically.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "lookup", "(Ljava/lang/String;I)Ljava/lang/String;",
                false);
        statically.visitInsn(Opcodes.ARETURN);
        end(statically);

        MethodVisitor onInstance = method(writer, "described", "()Ljava/lang/Object;");
        onInstance.visitMethodInsn(Opcodes.INVOKESTATIC, REGISTRY, "get", "()L" + REGISTRY + ";", false);
        onInstance.visitLdcInsn("k");
        onInstance.visitLdcInsn(7L);
        onInstance.visitMethodInsn(Opcodes.INVOKEVIRTUAL, REGISTRY, "describe", "(Ljava/lang/String;J)Ljava/lang/String;",
                false);
        onInstance.visitInsn(Opcodes.ARETURN);
        end(onInstance);
    }

    private static byte[] caller() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);
        widenedConstructor(writer);
        goneConstructor(writer);
        widenedMethod(writer);
        constructorReferences(writer);
        oldGetters(writer);
        movedAcrossStatic(writer);
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    @DisplayName("a constructor whose parameter was widened is called in its new shape")
    void widenedConstructorIsFollowed() throws Exception {
        Object made = call(repaired(caller()), "widened");

        assertTrue(made instanceof Box3);
        assertEquals(2.0d, ((Box3) made).x, "the argument should have reached the constructor unchanged");
    }

    @Test
    @DisplayName("a constructor that is gone builds its object through one that is not")
    void goneConstructorStillBuilds() throws Exception {
        Object made = call(repaired(caller()), "rebuilt");

        assertNotNull(made, "the caller uses what it made, so it should not get null when anything can build it");
        assertTrue(made instanceof Box3);
    }

    @Test
    @DisplayName("a gone constructor whose result is discarded leaves the stack balanced")
    void discardedConstructorBalances() throws Exception {
        assertEquals("survived", call(repaired(caller()), "discarded"));
    }

    @Test
    @DisplayName("a method whose parameter was widened and result narrowed is called in its new shape")
    void widenedMethodIsFollowed() throws Exception {
        Object grown = call(repaired(caller()), "grown");

        assertTrue(grown instanceof Point3);
        assertEquals(7.0d, ((Point3) grown).x());
    }

    @Test
    @DisplayName("a constructor reference whose parameter was widened is pointed at the new constructor")
    void widenedConstructorReference() throws Exception {
        Object made = call(repaired(caller()), "referenced");

        assertTrue(made instanceof Box3);
        assertEquals(3.0d, ((Box3) made).x);
    }

    @Test
    @DisplayName("a reference to a constructor that is gone still builds an object")
    void goneConstructorReference() throws Exception {
        assertTrue(call(repaired(caller()), "referencedGone") instanceof Box3);
    }

    @Test
    @DisplayName("an old getter reads the saved value through its ...Or form, and the old default when absent")
    void oldGetterReadsSavedValue() throws Exception {
        Tag3 tag = new Tag3().put("level", 7);

        assertEquals(7, read("readInt", tag, "level"), "a saved value must not read back as zero");
        assertEquals(0, read("readInt", tag, "missing"));
    }

    @Test
    @DisplayName("an old getter whose new form answers an Optional is unwrapped to the old default")
    void optionalGetterIsUnwrapped() throws Exception {
        Tag3 tag = new Tag3().put("name", "octo");

        assertEquals("octo", read("readString", tag, "name"));
        assertEquals("", read("readString", tag, "missing"), "the old getter answered an empty string");
    }

    @Test
    @DisplayName("an old getter for a child tag gets the child, or an empty one — never the parent")
    void childGetterNeverAnswersTheParent() throws Exception {
        Tag3 child = new Tag3().put("x", 1);
        Tag3 parent = new Tag3().put("child", child);

        assertSame(child, read("readCompound", parent, "child"));
        Object missing = read("readCompound", parent, "missing");
        assertTrue(missing instanceof Tag3 && ((Tag3) missing).isEmpty());
        assertNotSame(parent, missing);
    }

    @Test
    @DisplayName("a type id the new getters no longer take is let go of")
    void droppedTypeIdIsLetGo() throws Exception {
        Tag3 tag = new Tag3().put("items", new Tag3().put("a", 1)).put("flag", 1);

        assertEquals(Boolean.TRUE, read("has", tag, "flag"));
        assertFalse(((Tag3) read("readList", tag, "items")).isEmpty());
    }

    @Test
    @DisplayName("a static call with arguments to what is now an instance method goes through the singleton")
    void staticCallWithArgumentsReachesInstance() throws Exception {
        assertEquals("ababab", call(repaired(caller()), "lookedUp"));
    }

    @Test
    @DisplayName("an instance call with arguments to what is now static drops the receiver")
    void instanceCallWithArgumentsReachesStatic() throws Exception {
        assertEquals("k#7", call(repaired(caller()), "described"));
    }

    @Test
    @DisplayName("a class nothing can build hands back null instead of throwing")
    void unbuildableIsNull() {
        assertNull(GapBridge.construct(Runnable.class, new Object[0]));
        assertNull(GapBridge.construct(java.util.AbstractList.class, new Object[0]));
    }
}
