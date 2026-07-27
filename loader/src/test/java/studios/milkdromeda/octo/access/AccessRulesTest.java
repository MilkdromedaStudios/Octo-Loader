package studios.milkdromeda.octo.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;

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
    @DisplayName("Fabric class tweakers")
    class ClassTweakers {
        @Test
        @DisplayName("the classTweaker header is read, not just accessWidener")
        void readsTheNewerHeader() {
            AccessRules rules = AccessWidenerReader.read("""
                    classTweaker\tv2\tnamed
                    transitive-extendable\tclass\tnet/minecraft/world/item/crafting/Ingredient
                    """, "test", MappingSet.EMPTY);

            assertTrue(rules.forClass("net/minecraft/world/item/crafting/Ingredient").removeFinal(),
                    "current Fabric API ships class tweakers, and this is where Ingredient stops being final");
        }

        @Test
        @DisplayName("inject-interface adds the interface to the target")
        void readsInterfaceInjection() {
            AccessRules rules = AccessWidenerReader.read("""
                    classTweaker\tv1\tnamed
                    inject-interface\tnet/minecraft/world/entity/Entity\tcom/example/Extra
                    """, "test", MappingSet.EMPTY);

            assertEquals(Set.of(new InjectedInterface("com/example/Extra", "Lcom/example/Extra;")),
                    rules.interfacesFor("net/minecraft/world/entity/Entity"));
            assertTrue(rules.touches("net/minecraft/world/entity/Entity"));
        }

        @Test
        @DisplayName("a generic interface keeps its arguments out of the name")
        void separatesGenericsFromTheName() {
            AccessRules rules = AccessWidenerReader.read("""
                    classTweaker\tv1\tnamed
                    inject-interface\tnet/minecraft/client/model/Model\tcom/example/FabricModel<TT;>
                    """, "test", MappingSet.EMPTY);

            InjectedInterface injected =
                    rules.interfacesFor("net/minecraft/client/model/Model").iterator().next();

            // The name goes in the interface list, where "<" is illegal; the
            // whole thing goes in the signature, where it is required.
            assertEquals("com/example/FabricModel", injected.rawName());
            assertEquals("Lcom/example/FabricModel<TT;>;", injected.signature());
            assertTrue(injected.hasGenerics());
        }

        @Test
        @DisplayName("an interface name that could never load is refused rather than injected")
        void refusesAnUnusableName() {
            AccessRules rules = AccessWidenerReader.read("""
                    classTweaker\tv1\tnamed
                    inject-interface\tnet/minecraft/Foo\tcom/example/Broken;
                    inject-interface\tnet/minecraft/Foo\tcom/example/Fine
                    """, "test", MappingSet.EMPTY);

            assertEquals(Set.of(new InjectedInterface("com/example/Fine", "Lcom/example/Fine;")),
                    rules.interfacesFor("net/minecraft/Foo"));
        }

        @Test
        @DisplayName("an unsupported directive is dropped without losing the rest of the file")
        void skipsWhatItCannotDo() {
            AccessRules rules = AccessWidenerReader.read("""
                    classTweaker\tv2\tnamed
                    extend-enum\tnet/minecraft/world/item/Rarity\texample
                    accessible\tclass\tnet/minecraft/Foo
                    """, "test", MappingSet.EMPTY);

            assertTrue(rules.forClass("net/minecraft/Foo").makePublic());
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
            assertFalse(rules.forField("net/minecraft/client/Minecraft", "player", "I").finalIfPrivate());
        }
    }

    @Test
    @DisplayName("widening an inherited method does not make it final, so overrides still link")
    void neverFinalisesAnInheritedMethod() throws Exception {
        // The exact shape that crashed a real launch: a widener names a method
        // the game's own subclass overrides. Marking it final there turns every
        // override into an IncompatibleClassChangeError when the subclass loads.
        Map<String, byte[]> compiled = SourceCompiler.compile(Map.of(
                "game.Living", """
                        package game;
                        public class Living {
                            protected void hurtArmor(float amount) { }
                            private String secret() { return "hidden"; }
                        }
                        """,
                "game.Player", """
                        package game;
                        public class Player extends Living {
                            @Override protected void hurtArmor(float amount) { }
                        }
                        """));

        AccessRules rules = AccessWidenerReader.read("""
                accessWidener\tv2\tnamed
                accessible\tmethod\tgame/Living\thurtArmor\t(F)V
                accessible\tmethod\tgame/Living\tsecret\t()Ljava/lang/String;
                """, "test", MappingSet.EMPTY);

        AccessRuleTransformer transformer = new AccessRuleTransformer(rules);
        TransformContext context = TransformContext.of(null, name -> true);

        ByteClassLoader loader = new ByteClassLoader(getClass().getClassLoader())
                .define("game.Living", transformer.transform("game/Living", compiled.get("game.Living"), context))
                .define("game.Player", compiled.get("game.Player"));

        Class<?> living = loader.loadClass("game.Living");

        assertTrue(Modifier.isPublic(living.getDeclaredMethod("hurtArmor", float.class).getModifiers()),
                "the inherited method should have been made public");
        assertFalse(Modifier.isFinal(living.getDeclaredMethod("hurtArmor", float.class).getModifiers()),
                "but it must not have been made final");
        assertTrue(Modifier.isFinal(living.getDeclaredMethod("secret").getModifiers()),
                "a widened private method is still finalised, as Fabric does");

        // The real proof: the subclass links. Without the fix this throws
        // IncompatibleClassChangeError: overrides final method.
        assertEquals(living, loader.loadClass("game.Player").getSuperclass());
    }

    @Test
    @DisplayName("a class with a generic interface injected into it still loads")
    void injectsAGenericInterfaceWithoutBreakingTheClass() throws Exception {
        // Reproduces a real crash during the game's bootstrap:
        //   ClassFormatError: Illegal class name
        //   "net/fabricmc/.../FabricModel" in class file net/minecraft/client/model/Model
        // The declared interface carried generics, and the generics went into
        // the interface list, where the JVM will not have them.
        Map<String, byte[]> compiled = SourceCompiler.compile(Map.of(
                "game.Model", """
                        package game;
                        public class Model { public String describe() { return "model"; } }
                        """,
                "api.FabricModel", """
                        package api;
                        public interface FabricModel<T> { default T nothing() { return null; } }
                        """));

        AccessRules rules = AccessWidenerReader.read("""
                classTweaker\tv1\tnamed
                inject-interface\tgame/Model\tapi/FabricModel<Ljava/lang/String;>
                """, "test", MappingSet.EMPTY);

        byte[] injected = new AccessRuleTransformer(rules).transform("game/Model",
                compiled.get("game.Model"), TransformContext.of(null, name -> true));

        Class<?> model = new ByteClassLoader(getClass().getClassLoader())
                .define("game.Model", injected)
                .define("api.FabricModel", compiled.get("api.FabricModel"))
                .loadClass("game.Model");

        assertEquals("api.FabricModel", model.getInterfaces()[0].getName(),
                "the target should implement the injected interface");
        assertEquals("api.FabricModel<java.lang.String>",
                model.getGenericInterfaces()[0].getTypeName(),
                "and the generics should have gone to the signature, where they belong");
        assertEquals("model", model.getDeclaredMethod("describe")
                .invoke(model.getDeclaredConstructor().newInstance()),
                "the class should still work");
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
