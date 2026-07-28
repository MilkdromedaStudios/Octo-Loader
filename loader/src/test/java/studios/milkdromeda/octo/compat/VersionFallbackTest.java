package studios.milkdromeda.octo.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import studios.milkdromeda.octo.compat.api.Equivalents;
import studios.milkdromeda.octo.transform.MissingClassTransformer;
import studios.milkdromeda.octo.transform.PhantomClasses;
import studios.milkdromeda.octo.transform.TransformContext;

/**
 * A mod naming a class this Minecraft does not have.
 *
 * <p>The interesting property under test is that the same table is right on
 * every version: what happens depends entirely on which of the names the running
 * game turns out to have, and on a runtime that has the original nothing happens
 * at all.
 */
class VersionFallbackTest {
    private static final String CALLER = "com/example/mod/Caller";

    /** A mod class that calls a static method on {@code owner}. */
    private static byte[] callerOf(String owner) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CALLER, null, "java/lang/Object", null);

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "probe", "()V",
                null, null);
        probe.visitCode();
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "draw", "()V", false);
        probe.visitInsn(Opcodes.RETURN);
        probe.visitMaxs(0, 0);
        probe.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String calledOwner(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);

        for (MethodNode method : node.methods) {
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals("draw")) {
                    return call.owner;
                }
            }
        }

        return null;
    }

    private static byte[] rewrite(Equivalents table, byte[] bytes, Predicate<String> classExists) {
        return new MissingClassTransformer(table)
                .transform(CALLER, bytes, TransformContext.of(null, classExists));
    }

    private static final String LAYERS = """
            {
              "classes": [
                { "name": "game/LayeredDraw$Layer",
                  "alternatives": ["game/LayeredDraw$Layer", "game/LayeredDrawer$Layer"],
                  "stub": "interface",
                  "note": "arrived in a later version" },
                { "name": "game/ItemStackHandler", "stub": "class" },
                { "name": "game/NoAnswer" }
              ],
              "methods": [
                { "owner": "game/Screen", "name": "shouldTrigger", "alternatives": ["isUsingItem"] }
              ],
              "fields": [
                { "name": "entries", "alternatives": ["details"] }
              ]
            }
            """;

    private static Equivalents table() {
        return Equivalents.parse(new StringReader(LAYERS), "test");
    }

    @Nested
    @DisplayName("reading a table")
    class Reading {
        @Test
        @DisplayName("an entry that lists itself among its alternatives does not send the resolver in a circle")
        void selfReferenceIsDropped() {
            Equivalents.ClassEntry entry = table().classEntry("game/LayeredDraw$Layer");

            assertNotNull(entry);
            assertEquals(List.of("game/LayeredDrawer$Layer"), entry.alternatives());
        }

        @Test
        @DisplayName("an entry says what may be fabricated, and says nothing by default")
        void stubKindIsRead() {
            Equivalents parsed = table();

            assertEquals(Equivalents.Stub.INTERFACE, parsed.classEntry("game/LayeredDraw$Layer").stub());
            assertEquals(Equivalents.Stub.CLASS, parsed.classEntry("game/ItemStackHandler").stub());
            assertEquals(Equivalents.Stub.NONE, parsed.classEntry("game/NoAnswer").stub());
            assertNull(parsed.classEntry("game/NeverMentioned"));
        }

        @Test
        @DisplayName("a member entry with no owner answers for any class that asks")
        void memberAliasesAreOwnerOptional() {
            Equivalents parsed = table();

            assertEquals(List.of("isUsingItem"), parsed.methodAliases("game/Screen", "shouldTrigger"));
            assertTrue(parsed.methodAliases("game/Other", "shouldTrigger").isEmpty());
            assertEquals(List.of("details"), parsed.fieldAliases("anything/At/All", "entries"));
        }

        @Test
        @DisplayName("the table Octo ships parses, and covers the classes the crash reports named")
        void bundledTableIsReadable() {
            Equivalents bundled = Equivalents.load(null);

            assertFalse(bundled.isEmpty(), "the bundled equivalents table should have been found on the class path");

            for (String name : Set.of("net/minecraft/client/gui/LayeredDraw$Layer",
                    "net/minecraft/client/renderer/entity/ItemRenderer",
                    "net/minecraft/world/entity/vehicle/MinecartFurnace",
                    "net/neoforged/neoforge/items/ItemStackHandler",
                    "net/neoforged/neoforge/fluids/FluidInteractionRegistry",
                    "com/tterrag/registrate/AbstractRegistrate")) {
                assertNotNull(bundled.classEntry(name), name + " should be in the bundled table");
            }
        }
    }

    @Nested
    @DisplayName("substituting a class")
    class Substituting {
        @Test
        @DisplayName("a reference to a class this version does not have is aimed at the one it does")
        void absentClassIsRedirected() {
            byte[] rewritten = rewrite(table(), callerOf("game/LayeredDraw$Layer"),
                    name -> name.equals("game/LayeredDrawer$Layer"));

            assertEquals("game/LayeredDrawer$Layer", calledOwner(rewritten));
        }

        @Test
        @DisplayName("on a version that has the class, nothing is touched at all")
        void presentClassIsLeftAlone() {
            byte[] original = callerOf("game/LayeredDraw$Layer");

            assertSame(original, rewrite(table(), original, name -> true),
                    "the table must be inert where the original class exists");
        }

        @Test
        @DisplayName("when no alternative exists either, the reference is left for a stand-in to answer")
        void nothingToSubstituteLeavesTheReference() {
            byte[] original = callerOf("game/LayeredDraw$Layer");

            assertSame(original, rewrite(table(), original, name -> false));
        }

        @Test
        @DisplayName("a class nobody wrote a rule for is never substituted")
        void unknownClassIsUntouched() {
            byte[] original = callerOf("game/SomethingElse");

            assertSame(original, rewrite(table(), original, name -> false));
        }
    }

    @Nested
    @DisplayName("standing in for a class")
    class StandingIn {
        @Test
        @DisplayName("what the table calls a class is generated as one, so a mod can construct it")
        void declaredClassIsInstantiable() throws Exception {
            PhantomClasses phantoms = new PhantomClasses();
            phantoms.useEquivalents(table());

            assertNotNull(phantoms.declareFromTable("game/ItemStackHandler"));

            byte[] bytes = phantoms.generate("game/ItemStackHandler");
            Class<?> type = new Defining().define("game.ItemStackHandler", bytes);

            assertFalse(type.isInterface(), "a mod says new about this one");
            assertNotNull(type.getDeclaredConstructor().newInstance());
        }

        @Test
        @DisplayName("what the table calls an interface is generated as one, so a mod can implement it")
        void declaredInterfaceIsAnInterface() {
            PhantomClasses phantoms = new PhantomClasses();
            phantoms.useEquivalents(table());
            phantoms.declareFromTable("game/LayeredDraw$Layer");

            Class<?> type = new Defining().define("game.LayeredDraw$Layer",
                    phantoms.generate("game/LayeredDraw$Layer"));

            assertTrue(type.isInterface());
        }

        @Test
        @DisplayName("a class the table declines to stand in for is not fabricated behind the mod's back")
        void undeclaredClassIsNotFabricated() {
            PhantomClasses phantoms = new PhantomClasses();
            phantoms.useEquivalents(table());

            assertNull(phantoms.declareFromTable("game/NoAnswer"));
            assertNull(phantoms.declareFromTable("game/NeverMentioned"));
        }

        @Test
        @DisplayName("a current mod is only answered for what Octo is responsible for")
        void observationCanBeLimited() {
            PhantomClasses phantoms = new PhantomClasses();
            phantoms.observe(callerOf("com/somebody/Elses"), name -> false,
                    name -> name.startsWith("net/neoforged/"));

            assertFalse(phantoms.has("com/somebody/Elses"),
                    "a missing class in a mod's own dependency is that mod's problem, not Octo's");

            phantoms.observe(callerOf("net/neoforged/neoforge/items/ItemStackHandler"), name -> false,
                    name -> name.startsWith("net/neoforged/"));

            assertTrue(phantoms.has("net/neoforged/neoforge/items/ItemStackHandler"));
        }

        @Test
        @DisplayName("a stand-in planned from the mod's bytecode carries the methods the mod calls")
        void plannedStandInHasTheRightShape() throws Exception {
            PhantomClasses phantoms = new PhantomClasses();
            phantoms.observe(callerOf("game/ItemStackHandler"), name -> false, name -> true);

            Class<?> type = new Defining().define("game.ItemStackHandler",
                    phantoms.generate("game/ItemStackHandler"));

            assertNotNull(type.getDeclaredMethod("draw"),
                    "the method the mod called should exist on the stand-in");
        }
    }

    /** Defines what was generated, so the JVM's own verifier passes judgement on it. */
    private static final class Defining extends ClassLoader {
        Defining() {
            super(VersionFallbackTest.class.getClassLoader());
        }

        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
