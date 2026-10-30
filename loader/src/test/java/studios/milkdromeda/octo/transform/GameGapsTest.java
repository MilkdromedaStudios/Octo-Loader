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
    private static final String ID = "net/minecraft/octotest/Id3";

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

    /** {@code new ResourceLocation("mod", "thing")} and {@code new ResourceLocation("mod:thing")}, as 1.20 wrote them. */
    private static void privateConstructors(ClassWriter writer) {
        MethodVisitor twoArguments = method(writer, "identified", "()Ljava/lang/Object;");
        twoArguments.visitTypeInsn(Opcodes.NEW, ID);
        twoArguments.visitInsn(Opcodes.DUP);
        twoArguments.visitLdcInsn("mod");
        twoArguments.visitLdcInsn("thing");
        twoArguments.visitMethodInsn(Opcodes.INVOKESPECIAL, ID, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        twoArguments.visitInsn(Opcodes.ARETURN);
        end(twoArguments);

        MethodVisitor oneArgument = method(writer, "parsed", "()Ljava/lang/Object;");
        oneArgument.visitTypeInsn(Opcodes.NEW, ID);
        oneArgument.visitInsn(Opcodes.DUP);
        oneArgument.visitLdcInsn("mod:other");
        oneArgument.visitMethodInsn(Opcodes.INVOKESPECIAL, ID, "<init>", "(Ljava/lang/String;)V", false);
        oneArgument.visitInsn(Opcodes.ARETURN);
        end(oneArgument);
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
        privateConstructors(writer);
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
    @DisplayName("a constructor that is private now is reached through the class's own factory")
    void privateConstructorUsesFactory() throws Exception {
        assertEquals("mod:thing", String.valueOf(call(repaired(caller()), "identified")),
                "fromNamespaceAndPath, not tryBuild, which answers null");
    }

    @Test
    @DisplayName("a constructor that is gone is reached through the factory taking the same arguments")
    void goneConstructorUsesFactory() throws Exception {
        assertEquals("mod:other", String.valueOf(call(repaired(caller()), "parsed")),
                "parse, not withDefaultNamespace, which would read the whole string as a path");
    }

    @Test
    @DisplayName("a private member reached from its own nest is left alone")
    void nestmatesKeepPrivateAccess() {
        String nestmate = ID + "$Builder";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, nestmate, null, "java/lang/Object", null);
        MethodVisitor build = method(writer, "build", "()Ljava/lang/Object;");
        build.visitTypeInsn(Opcodes.NEW, ID);
        build.visitInsn(Opcodes.DUP);
        build.visitLdcInsn("a");
        build.visitLdcInsn("b");
        build.visitMethodInsn(Opcodes.INVOKESPECIAL, ID, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V", false);
        build.visitInsn(Opcodes.ARETURN);
        end(build);
        writer.visitEnd();
        byte[] original = writer.toByteArray();

        MissingMemberTransformer gaps = MissingMemberTransformer.forTranslatedMods(
                new MemberIndex(GameGapsTest::classBytes), List.of("net/minecraft/octotest/"));

        assertSame(original, gaps.transform(nestmate, original, TransformContext.of(null, name -> true)),
                "an inner class may call its outer class's private constructor; nothing to repair");
    }

    /** Each field read the way 1.21 compiled it: one {@code String} per weathering state. */
    private static Class<?> copperReader(String... fields) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);

        for (String field : fields) {
            MethodVisitor read = method(writer, field, "()Ljava/lang/Object;");
            read.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/octotest/Copper3", field, "Ljava/lang/String;");
            read.visitInsn(Opcodes.ARETURN);
            end(read);
        }

        writer.visitEnd();
        return repaired(writer.toByteArray());
    }

    @Test
    @DisplayName("a copper block that became one of a collection's weathering states is read from it")
    void weatheringStatesAreReadFromTheirCollection() throws Exception {
        Class<?> reader = copperReader("COPPER_BLOCK", "EXPOSED_COPPER", "WAXED_COPPER_BLOCK",
                "WAXED_OXIDIZED_COPPER", "WEATHERED_CUT_COPPER", "WAXED_EXPOSED_CUT_COPPER", "COPPER_NOTHING");

        assertEquals("copper", call(reader, "COPPER_BLOCK"));
        assertEquals("exposed copper", call(reader, "EXPOSED_COPPER"), "EXPOSED_COPPER is COPPER_BLOCK's");
        assertEquals("waxed copper", call(reader, "WAXED_COPPER_BLOCK"));
        assertEquals("waxed oxidized copper", call(reader, "WAXED_OXIDIZED_COPPER"));
        assertEquals("weathered cut copper", call(reader, "WEATHERED_CUT_COPPER"));
        assertEquals("waxed exposed cut copper", call(reader, "WAXED_EXPOSED_CUT_COPPER"));
        assertNull(call(reader, "COPPER_NOTHING"), "a field with no collection behind it still reads a default");
    }

    @Test
    @DisplayName("a super() call to a constructor the game's superclass no longer has builds it another way")
    void goneSuperConstructorStillBuilds() throws Exception {
        String custom = "net/minecraft/octotest/Custom3";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, custom, null);

        // super("misc"), compiled when Custom3 took a category.
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitLdcInsn("misc");
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, custom, "<init>", "(Ljava/lang/String;)V", false);
        constructor.visitInsn(Opcodes.RETURN);
        end(constructor);

        MethodVisitor entry = method(writer, "entry", "()Ljava/lang/Object;");
        entry.visitTypeInsn(Opcodes.NEW, CALLER);
        entry.visitInsn(Opcodes.DUP);
        entry.visitMethodInsn(Opcodes.INVOKESPECIAL, CALLER, "<init>", "()V", false);
        entry.visitFieldInsn(Opcodes.GETFIELD, custom, "made", "Ljava/lang/String;");
        entry.visitInsn(Opcodes.ARETURN);
        end(entry);

        writer.visitEnd();

        assertEquals("made", call(repaired(writer.toByteArray()), "entry"),
                "the superclass is built through the constructor it has, and the object is usable");
    }

    @Test
    @DisplayName("a super() call whose arguments the superclass now takes in another order is reordered")
    void reorderedSuperConstructor() throws Exception {
        String gate = "net/minecraft/octotest/Gate3";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, gate, null);

        // super("pane", tag), compiled when Gate3 took its name first.
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(L" + TAG + ";)V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitLdcInsn("pane");
        constructor.visitVarInsn(Opcodes.ALOAD, 1);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, gate, "<init>", "(Ljava/lang/String;L" + TAG + ";)V",
                false);
        constructor.visitInsn(Opcodes.RETURN);
        end(constructor);

        MethodVisitor entry = method(writer, "entry", "()Ljava/lang/Object;");
        entry.visitTypeInsn(Opcodes.NEW, CALLER);
        entry.visitInsn(Opcodes.DUP);
        entry.visitTypeInsn(Opcodes.NEW, TAG);
        entry.visitInsn(Opcodes.DUP);
        entry.visitMethodInsn(Opcodes.INVOKESPECIAL, TAG, "<init>", "()V", false);
        entry.visitMethodInsn(Opcodes.INVOKESPECIAL, CALLER, "<init>", "(L" + TAG + ";)V", false);
        entry.visitInsn(Opcodes.ARETURN);
        end(entry);

        writer.visitEnd();

        net.minecraft.octotest.Gate3 built = (net.minecraft.octotest.Gate3) call(repaired(writer.toByteArray()),
                "entry");
        assertEquals("pane", built.name, "the name goes where the constructor takes it now");
        assertNotNull(built.tag, "and so does the tag, rather than a null in its place");
    }

    @Test
    @DisplayName("a constructor built at run time takes its arguments in the order it wants them")
    void runtimeConstructionReorders() {
        Tag3 tag = new Tag3();
        net.minecraft.octotest.Gate3 built = (net.minecraft.octotest.Gate3) GapBridge.construct(
                net.minecraft.octotest.Gate3.class, new Object[] { "pane", tag });

        assertEquals("pane", built.name);
        assertSame(tag, built.tag);
    }

    @Test
    @DisplayName("a made-up constructor argument is a constant of its type, not a null")
    void madeUpArgumentsAreConstants() throws Exception {
        String gate = "net/minecraft/octotest/Gate3";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, gate, null);

        // super(1, 2), compiled when Gate3 took two sound ids; nothing fits now.
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitInsn(Opcodes.ICONST_1);
        constructor.visitInsn(Opcodes.ICONST_2);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, gate, "<init>", "(II)V", false);
        constructor.visitInsn(Opcodes.RETURN);
        end(constructor);

        MethodVisitor entry = method(writer, "entry", "()Ljava/lang/Object;");
        entry.visitTypeInsn(Opcodes.NEW, CALLER);
        entry.visitInsn(Opcodes.DUP);
        entry.visitMethodInsn(Opcodes.INVOKESPECIAL, CALLER, "<init>", "()V", false);
        entry.visitFieldInsn(Opcodes.GETFIELD, gate, "wood", "Ljava/lang/String;");
        entry.visitInsn(Opcodes.ARETURN);
        end(entry);

        writer.visitEnd();

        assertEquals("oak", call(repaired(writer.toByteArray()), "entry"),
                "the gate is built with its type's first constant, where a null failed inside the constructor");
    }

    @Test
    @DisplayName("an is-setter a builder no longer has hands the builder back, so the chain goes on")
    void builderSettersNamedLikeQueriesHandBack() throws Exception {
        String builder = "net/minecraft/octotest/Builder3";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);

        // new Builder3().isViewBlocking("never").strength(1f).calls
        MethodVisitor entry = method(writer, "entry", "()Ljava/lang/Object;");
        entry.visitTypeInsn(Opcodes.NEW, builder);
        entry.visitInsn(Opcodes.DUP);
        entry.visitMethodInsn(Opcodes.INVOKESPECIAL, builder, "<init>", "()V", false);
        entry.visitLdcInsn("never");
        entry.visitMethodInsn(Opcodes.INVOKEVIRTUAL, builder, "isViewBlocking",
                "(Ljava/lang/String;)L" + builder + ";", false);
        entry.visitInsn(Opcodes.FCONST_1);
        entry.visitMethodInsn(Opcodes.INVOKEVIRTUAL, builder, "strength", "(F)L" + builder + ";", false);
        entry.visitFieldInsn(Opcodes.GETFIELD, builder, "calls", "I");
        entry.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        entry.visitInsn(Opcodes.ARETURN);
        end(entry);

        writer.visitEnd();

        assertEquals(1, call(repaired(writer.toByteArray()), "entry"));
    }

    @Test
    @DisplayName("a super call to what nothing above has any more is skipped, with a default for its result")
    void goneSuperCallIsSkipped() throws Exception {
        String block = "net/minecraft/octotest/Block3";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, block, null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, block, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        end(constructor);

        // super.save(7); return super.count() + super.shape().length();
        MethodVisitor run = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()I", null, null);
        run.visitCode();
        run.visitVarInsn(Opcodes.ALOAD, 0);
        run.visitIntInsn(Opcodes.BIPUSH, 7);
        run.visitMethodInsn(Opcodes.INVOKESPECIAL, block, "save", "(I)V", false);
        run.visitVarInsn(Opcodes.ALOAD, 0);
        run.visitMethodInsn(Opcodes.INVOKESPECIAL, block, "count", "()I", false);
        run.visitVarInsn(Opcodes.ALOAD, 0);
        run.visitMethodInsn(Opcodes.INVOKESPECIAL, block, "shape", "()Ljava/lang/String;", false);
        run.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false);
        run.visitInsn(Opcodes.IADD);
        run.visitInsn(Opcodes.IRETURN);
        end(run);

        MethodVisitor entry = method(writer, "entry", "()Ljava/lang/Object;");
        entry.visitTypeInsn(Opcodes.NEW, CALLER);
        entry.visitInsn(Opcodes.DUP);
        entry.visitMethodInsn(Opcodes.INVOKESPECIAL, CALLER, "<init>", "()V", false);
        entry.visitMethodInsn(Opcodes.INVOKEVIRTUAL, CALLER, "run", "()I", false);
        entry.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        entry.visitInsn(Opcodes.ARETURN);
        end(entry);

        writer.visitEnd();

        assertEquals(4, call(repaired(writer.toByteArray()), "entry"),
                "save and count are gone and skipped; shape is still there and still called");
    }

    @Test
    @DisplayName("a dyed block that became one of a collection's colours is read from it")
    void coloursAreReadFromTheirCollection() throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);

        for (String field : List.of("YELLOW_WOOL", "LIGHT_BLUE_WOOL", "RED_SHULKER_BOX", "RED_NOTHING")) {
            MethodVisitor read = method(writer, field, "()Ljava/lang/Object;");
            read.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/octotest/Colours3", field, "Ljava/lang/String;");
            read.visitInsn(Opcodes.ARETURN);
            end(read);
        }

        writer.visitEnd();
        Class<?> reader = repaired(writer.toByteArray());

        assertEquals("yellow wool", call(reader, "YELLOW_WOOL"));
        assertEquals("light blue wool", call(reader, "LIGHT_BLUE_WOOL"));
        assertEquals("red box", call(reader, "RED_SHULKER_BOX"), "a shulker box is among the DYED_ ones");
        assertNull(call(reader, "RED_NOTHING"));
    }

    @Test
    @DisplayName("a field that was closed is reached through its accessor, or by reflection")
    void closedFieldsAreReached() throws Exception {
        String level = "net/minecraft/octotest/Level3";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);

        MethodVisitor clientSide = method(writer, "clientSide", "()Ljava/lang/Object;");
        clientSide.visitTypeInsn(Opcodes.NEW, level);
        clientSide.visitInsn(Opcodes.DUP);
        clientSide.visitInsn(Opcodes.ICONST_1);
        clientSide.visitMethodInsn(Opcodes.INVOKESPECIAL, level, "<init>", "(Z)V", false);
        clientSide.visitFieldInsn(Opcodes.GETFIELD, level, "isClientSide", "Z");
        clientSide.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;",
                false);
        clientSide.visitInsn(Opcodes.ARETURN);
        end(clientSide);

        // level.age = 9; return level.age, with no accessor for either.
        MethodVisitor age = method(writer, "age", "()Ljava/lang/Object;");
        age.visitTypeInsn(Opcodes.NEW, level);
        age.visitInsn(Opcodes.DUP);
        age.visitInsn(Opcodes.ICONST_0);
        age.visitMethodInsn(Opcodes.INVOKESPECIAL, level, "<init>", "(Z)V", false);
        age.visitVarInsn(Opcodes.ASTORE, 0);
        age.visitVarInsn(Opcodes.ALOAD, 0);
        age.visitIntInsn(Opcodes.BIPUSH, 9);
        age.visitFieldInsn(Opcodes.PUTFIELD, level, "age", "I");
        age.visitVarInsn(Opcodes.ALOAD, 0);
        age.visitFieldInsn(Opcodes.GETFIELD, level, "age", "I");
        age.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        age.visitInsn(Opcodes.ARETURN);
        end(age);

        MethodVisitor motd = method(writer, "motd", "()Ljava/lang/Object;");
        motd.visitFieldInsn(Opcodes.GETSTATIC, level, "motd", "Ljava/lang/String;");
        motd.visitInsn(Opcodes.ARETURN);
        end(motd);

        // Protected, read from a class that is no subclass.
        MethodVisitor name = method(writer, "name", "()Ljava/lang/Object;");
        name.visitTypeInsn(Opcodes.NEW, level);
        name.visitInsn(Opcodes.DUP);
        name.visitInsn(Opcodes.ICONST_0);
        name.visitMethodInsn(Opcodes.INVOKESPECIAL, level, "<init>", "(Z)V", false);
        name.visitFieldInsn(Opcodes.GETFIELD, level, "name", "Ljava/lang/String;");
        name.visitInsn(Opcodes.ARETURN);
        end(name);

        writer.visitEnd();
        Class<?> reader = repaired(writer.toByteArray());

        assertEquals(true, call(reader, "clientSide"), "isClientSide() answers for the closed field");
        assertEquals(9, call(reader, "age"), "with no accessor, the field is written and read by reflection");
        assertEquals("hello", call(reader, "motd"), "a static field's static getter");
        assertEquals("level", call(reader, "name"));
    }

    @Test
    @DisplayName("a factory or constant that moved to the type's plural holder is reached there")
    void movedToPluralHolder() throws Exception {
        String paint = "net/minecraft/octotest/Paint3";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);

        // Paint3.of("blue"), compiled before of moved to Paint3s.
        MethodVisitor factory = method(writer, "factory", "()Ljava/lang/Object;");
        factory.visitLdcInsn("blue");
        factory.visitMethodInsn(Opcodes.INVOKESTATIC, paint, "of", "(Ljava/lang/String;)L" + paint + ";", false);
        factory.visitFieldInsn(Opcodes.GETFIELD, paint, "colour", "Ljava/lang/String;");
        factory.visitInsn(Opcodes.ARETURN);
        end(factory);

        // Paint3.RED, likewise.
        MethodVisitor constant = method(writer, "constant", "()Ljava/lang/Object;");
        constant.visitFieldInsn(Opcodes.GETSTATIC, paint, "RED", "L" + paint + ";");
        constant.visitFieldInsn(Opcodes.GETFIELD, paint, "colour", "Ljava/lang/String;");
        constant.visitInsn(Opcodes.ARETURN);
        end(constant);

        writer.visitEnd();
        Class<?> caller = repaired(writer.toByteArray());

        assertEquals("blue", call(caller, "factory"));
        assertEquals("red", call(caller, "constant"));
    }

    @Test
    @DisplayName("a class nothing can build hands back null instead of throwing")
    void unbuildableIsNull() {
        assertNull(GapBridge.construct(Runnable.class, new Object[0]));
        assertNull(GapBridge.construct(java.util.AbstractList.class, new Object[0]));
    }

    @Test
    @DisplayName("a method no longer told where is called without the position it was given")
    void placeArgumentsAreDropped() throws Exception {
        String state = "net/minecraft/octotest/State3";
        String pos = "net/minecraft/core/BlockPos";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);

        // new State3().light(new BlockPos(1, 2, 3), new Point3(4)), compiled
        // when the state still had to be told where it was.
        MethodVisitor method = method(writer, "light", "()Ljava/lang/Object;");
        method.visitTypeInsn(Opcodes.NEW, state);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, state, "<init>", "()V", false);
        method.visitTypeInsn(Opcodes.NEW, pos);
        method.visitInsn(Opcodes.DUP);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.ICONST_2);
        method.visitInsn(Opcodes.ICONST_3);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, pos, "<init>", "(III)V", false);
        method.visitTypeInsn(Opcodes.NEW, POINT);
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn(4.0d);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, POINT, "<init>", "(D)V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, state, "light",
                "(L" + pos + ";Lnet/minecraft/octotest/Shape3;)D", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false);
        method.visitInsn(Opcodes.ARETURN);
        end(method);
        writer.visitEnd();

        assertEquals(40.0d, call(repaired(writer.toByteArray()), "light"),
                "the side is passed on, not a default answered");
    }
}
