package studios.milkdromeda.octo.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import studios.milkdromeda.octo.compat.mapping.MappingSet;
import studios.milkdromeda.octo.testkit.ByteClassLoader;
import studios.milkdromeda.octo.testkit.SourceCompiler;
import studios.milkdromeda.octo.transform.TransformContext;

/** Both access-rule dialects, and what they do to a real class. */
class AccessRulesTest {
    @Nested
    @DisplayName("Fabric access wideners")
    class Wideners {
        @Test
        @DisplayName("accessible, extendable and mutable are read, comments and blank lines are not")
        void readsEveryVerb() {
            AccessRules rules = AccessWidenerReader.read("""
                    accessWidener\tv2\tnamed
                    # a comment

                    accessible\tmethod\tnet/minecraft/Foo\tbar\t()V
                    extendable\tclass\tnet/minecraft/Baz
                    mutable\tfield\tnet/minecraft/Foo\tcount\tI
                    """, "test", MappingSet.EMPTY);

            assertTrue(rules.forMethod("net/minecraft/Foo", "bar", "()V").makePublic());
            assertTrue(rules.forClass("net/minecraft/Baz").removeFinal());
            assertTrue(rules.forField("net/minecraft/Foo", "count", "I").removeFinal());

            // Reaching a member implies reaching the class that declares it.
            assertTrue(rules.forClass("net/minecraft/Foo").makePublic());
        }

        @Test
        @DisplayName("a transitive rule is an ordinary rule at run time")
        void transitiveIsTheSameAtRuntime() {
            AccessRules rules = AccessWidenerReader.read("""
                    accessWidener\tv2\tnamed
                    transitive-extendable\tclass\tnet/minecraft/Item
                    """, "test", MappingSet.EMPTY);

            assertTrue(rules.forClass("net/minecraft/Item").removeFinal());
        }

        @Test
        @DisplayName("a malformed line is dropped, and the rest of the file is still read")
        void survivesGarbage() {
            AccessRules rules = AccessWidenerReader.read("""
                    accessWidener\tv2\tnamed
                    nonsense\tclass\tnet/minecraft/Foo
                    accessible\tmethod\tnet/minecraft/Foo
                    accessible\tclass\tnet/minecraft/Bar
                    """, "test", MappingSet.EMPTY);

            assertTrue(rules.forClass("net/minecraft/Bar").makePublic());
            assertFalse(rules.touches("net/minecraft/Foo"));
        }

        @Test
        @DisplayName("a file without the header is ignored rather than half-read")
        void requiresTheHeader() {
            assertTrue(AccessWidenerReader.read("accessible\tclass\tnet/minecraft/Foo", "test", MappingSet.EMPTY)
                    .isEmpty());
        }
    }

    @Nested
    @DisplayName("Forge access transformers")
    class Transformers {
        @Test
        @DisplayName("classes, members and both wildcards")
        void readsEveryForm() {
            AccessRules rules = AccessTransformerReader.read("""
                    public-f net.minecraft.world.level.Level
                    public net.minecraft.client.Minecraft player
                    protected net.minecraft.client.Minecraft tick()V
                    public net.minecraft.server.MinecraftServer *
                    public net.minecraft.server.MinecraftServer *()
                    """, "test", MappingSet.EMPTY);

            assertTrue(rules.forClass("net/minecraft/world/level/Level").makePublic());
            assertTrue(rules.forClass("net/minecraft/world/level/Level").removeFinal());
            assertTrue(rules.forField("net/minecraft/client/Minecraft", "player", "Lnet/minecraft/Player;")
                    .makePublic());
            assertTrue(rules.forMethod("net/minecraft/client/Minecraft", "tick", "()V").makeProtected());
            assertTrue(rules.forField("net/minecraft/server/MinecraftServer", "anything", "I").makePublic());
            assertTrue(rules.forMethod("net/minecraft/server/MinecraftServer", "anything", "()V").makePublic());
        }

        @Test
        @DisplayName("a rule that would narrow access is dropped")
        void neverNarrows() {
            AccessRules rules = AccessTransformerReader.read("""
                    private net.minecraft.client.Minecraft player
                    """, "test", MappingSet.EMPTY);

            assertFalse(rules.forField("net/minecraft/client/Minecraft", "player", "I").makePublic());
            assertFalse(rules.forField("net/minecraft/client/Minecraft", "player", "I").makeFinal());
        }
    }

    @Test
    @DisplayName("the rules are applied to real bytecode, and both dialects reach the same class")
    void widensRealBytecode() throws Exception {
        Map<String, byte[]> compiled = SourceCompiler.compile(Map.of(
                "game.Target", """
                        package game;
                        public final class Target {
                            private static final int COUNT = 1;
                            private String secret() { return "hidden"; }
                        }
                        """));

        AccessRules rules = AccessRules.builder()
                // What a Fabric mod's widener says...
                .merge(AccessWidenerReader.read("""
                        accessWidener\tv2\tnamed
                        accessible\tmethod\tgame/Target\tsecret\t()Ljava/lang/String;
                        """, "widener", MappingSet.EMPTY))
                // ...and what a Forge mod's transformer says about the same class.
                .merge(AccessTransformerReader.read("""
                        public-f game.Target
                        public game.Target COUNT
                        """, "transformer", MappingSet.EMPTY))
                .build();

        byte[] widened = new AccessRuleTransformer(rules).transform("game/Target",
                compiled.get("game.Target"), TransformContext.of(null, name -> true));

        Class<?> type = new ByteClassLoader(getClass().getClassLoader())
                .define("game.Target", widened)
                .loadClass("game.Target");

        assertFalse(Modifier.isFinal(type.getModifiers()), "the class should no longer be final");

        Method secret = type.getDeclaredMethod("secret");
        assertTrue(Modifier.isPublic(secret.getModifiers()), "the private method should be public");

        Field count = type.getDeclaredField("COUNT");
        assertTrue(Modifier.isPublic(count.getModifiers()), "the private field should be public");
        assertTrue(Modifier.isFinal(count.getModifiers()),
                "widening visibility should not have removed final from the field");

        assertEquals("hidden", secret.invoke(type.getDeclaredConstructor().newInstance()),
                "the widened method should still do what it did");
    }
}
