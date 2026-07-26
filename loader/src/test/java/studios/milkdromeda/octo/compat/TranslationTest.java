package studios.milkdromeda.octo.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studios.milkdromeda.octo.compat.api.ApiRuleSet;
import studios.milkdromeda.octo.compat.mapping.MappingReaders;
import studios.milkdromeda.octo.compat.mapping.MappingRegistry;
import studios.milkdromeda.octo.compat.mapping.MappingSet;
import studios.milkdromeda.octo.testkit.ByteClassLoader;
import studios.milkdromeda.octo.testkit.SourceCompiler;
import studios.milkdromeda.octo.transform.ApiMigrationTransformer;
import studios.milkdromeda.octo.transform.PhantomClasses;
import studios.milkdromeda.octo.transform.RemapTransformer;
import studios.milkdromeda.octo.transform.TransformContext;
import studios.milkdromeda.octo.transform.TransformPipeline;

/**
 * The translation layer, exercised on real bytecode.
 *
 * <p>Each test compiles a class against an API that will not be there at run
 * time, translates it, then loads and runs it against the API that is. If the
 * translation is wrong the class does not link, so these tests fail loudly
 * rather than subtly.
 */
class TranslationTest {
    @TempDir
    Path directory;

    @AfterEach
    void tearDown() {
        MappingRegistry.reset();
    }

    // ------------------------------------------------------------- mappings

    @Test
    @DisplayName("Tiny v2 mappings are read, including the column the caller asks for")
    void readsTinyV2() throws IOException {
        String tiny = String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary\tnamed",
                "c\tbao\tnet/minecraft/class_310\tnet/minecraft/client/Minecraft",
                "\tm\t()Lbao;\ta\tmethod_1551\tgetInstance",
                "\tf\tLbao;\tS\tfield_1724\tplayer") + "\n";

        MappingSet officialToNamed = MappingReaders.read(
                new ByteArrayInputStream(tiny.getBytes(StandardCharsets.UTF_8)), "test", "official", "named");

        assertEquals("net/minecraft/client/Minecraft", officialToNamed.mapClass("bao"));
        assertEquals("getInstance", officialToNamed.mapMethod("bao", "a", "()Lbao;"));
        assertEquals("player", officialToNamed.mapField("bao", "S", "Lbao;"));

        MappingSet intermediaryToNamed = MappingReaders.read(
                new ByteArrayInputStream(tiny.getBytes(StandardCharsets.UTF_8)), "test", "intermediary", "named");

        assertEquals("net/minecraft/client/Minecraft",
                intermediaryToNamed.mapClass("net/minecraft/class_310"));
    }

    @Test
    @DisplayName("SRG and TSRG mappings are read")
    void readsSrgAndTsrg() throws IOException {
        String srg = String.join("\n",
                "CL: net/minecraft/client/Minecraft bao",
                "FD: net/minecraft/client/Minecraft/thePlayer bao/g",
                "MD: net/minecraft/client/Minecraft/getMinecraft ()Lnet/minecraft/client/Minecraft; bao/x ()Lbao;") + "\n";

        MappingSet fromSrg = MappingReaders.read(
                new ByteArrayInputStream(srg.getBytes(StandardCharsets.UTF_8)), "srg", null, null);

        assertEquals("bao", fromSrg.mapClass("net/minecraft/client/Minecraft"));
        assertEquals("x", fromSrg.mapMethod("net/minecraft/client/Minecraft", "getMinecraft",
                "()Lnet/minecraft/client/Minecraft;"));

        String tsrg = String.join("\n",
                "net/minecraft/client/Minecraft bao",
                "\tthePlayer g",
                "\tgetMinecraft ()Lnet/minecraft/client/Minecraft; x") + "\n";

        MappingSet fromTsrg = MappingReaders.read(
                new ByteArrayInputStream(tsrg.getBytes(StandardCharsets.UTF_8)), "tsrg", null, null);

        assertEquals("bao", fromTsrg.mapClass("net/minecraft/client/Minecraft"));
        assertEquals("g", fromTsrg.mapField("net/minecraft/client/Minecraft", "thePlayer", null));
    }

    @Test
    @DisplayName("mapping hops compose, so a mod can cross several eras in one rewrite")
    void mappingsCompose() {
        MappingSet first = MappingSet.builder("searge->official")
                .mapClass("net/minecraft/client/Minecraft", "bao")
                .mapMethod("net/minecraft/client/Minecraft", "func_71410_x", "()Lbao;", "x")
                .build();

        MappingSet second = MappingSet.builder("official->mojang")
                .mapClass("bao", "net/minecraft/client/Minecraft")
                .mapMethod("bao", "x", "()Lnet/minecraft/client/Minecraft;", "getInstance")
                .build();

        MappingSet composed = first.then(second);

        assertEquals("net/minecraft/client/Minecraft", composed.mapClass("net/minecraft/client/Minecraft"));
        assertEquals("getInstance",
                composed.mapMethod("net/minecraft/client/Minecraft", "func_71410_x", "()Lbao;"));
    }

    @Test
    @DisplayName("the registry finds a route through intermediate namespaces")
    void registryRoutesBetweenNamespaces() throws IOException {
        Path mappings = directory.resolve("mappings");
        Files.createDirectories(mappings);

        Files.writeString(mappings.resolve("searge-to-official.tsrg"), String.join("\n",
                "net/minecraft/client/Minecraft bao",
                "\tfunc_71410_x ()Lnet/minecraft/client/Minecraft; x") + "\n");

        Files.writeString(mappings.resolve("official-to-mojang.tsrg"), String.join("\n",
                "bao net/minecraft/client/Minecraft",
                "\tx ()Lbao; getInstance") + "\n");

        MappingRegistry registry = MappingRegistry.load(mappings, "mojang");

        assertFalse(registry.isEmpty());

        MappingSet route = registry.route("searge", "mojang");
        assertEquals("getInstance", route.mapMethod("net/minecraft/client/Minecraft", "func_71410_x",
                "()Lnet/minecraft/client/Minecraft;"));

        // Tables are usable in both directions without the user supplying both.
        assertEquals("func_71410_x", registry.route("mojang", "searge")
                .mapMethod("net/minecraft/client/Minecraft", "getInstance", "()Lbao;"));
    }

    @Test
    @DisplayName("remapping rewrites a compiled call so it links against the new name")
    void remapsRealBytecode() throws Exception {
        Map<String, byte[]> old = SourceCompiler.compile(Map.of(
                "oldpkg.OldWorld", """
                        package oldpkg;
                        public class OldWorld {
                            public String oldName() { return "old"; }
                        }
                        """));

        Path stubs = directory.resolve("stubs");
        writeClasses(stubs, old);

        Map<String, byte[]> mod = SourceCompiler.compile(Map.of(
                "modpkg.Caller", """
                        package modpkg;
                        public class Caller {
                            public static String call(oldpkg.OldWorld world) { return world.oldName(); }
                        }
                        """), List.of(stubs.toString()));

        Map<String, byte[]> current = SourceCompiler.compile(Map.of(
                "newpkg.NewWorld", """
                        package newpkg;
                        public class NewWorld {
                            public String newName() { return "new"; }
                        }
                        """));

        MappingSet mappings = MappingSet.builder("test")
                .mapClass("oldpkg/OldWorld", "newpkg/NewWorld")
                .mapMethod("oldpkg/OldWorld", "oldName", "()Ljava/lang/String;", "newName")
                .build();

        byte[] translated = new RemapTransformer(mappings).transform("modpkg/Caller",
                mod.get("modpkg.Caller"), TransformContext.of(null, name -> true));

        ByteClassLoader loader = new ByteClassLoader(getClass().getClassLoader())
                .defineAll(current)
                .define("modpkg.Caller", translated);

        Class<?> world = loader.loadClass("newpkg.NewWorld");
        Class<?> caller = loader.loadClass("modpkg.Caller");
        Method call = caller.getMethod("call", world);

        assertEquals("new", call.invoke(null, world.getDeclaredConstructor().newInstance()),
                "the call should have been rewritten onto the class that exists now");
    }

    // ------------------------------------------------------------ API rules

    @Test
    @DisplayName("a renamed static factory is redirected: Minecraft.getMinecraft() to getInstance()")
    void appliesApiRule() throws Exception {
        Map<String, byte[]> oldApi = SourceCompiler.compile(Map.of(
                "game.Client", """
                        package game;
                        public class Client {
                            public static Client getClient() { return new Client(); }
                            public String describe() { return "old"; }
                        }
                        """));

        Path stubs = directory.resolve("old-api");
        writeClasses(stubs, oldApi);

        Map<String, byte[]> mod = SourceCompiler.compile(Map.of(
                "modpkg.Uses", """
                        package modpkg;
                        public class Uses {
                            public static String go() { return game.Client.getClient().describe(); }
                        }
                        """), List.of(stubs.toString()));

        Map<String, byte[]> newApi = SourceCompiler.compile(Map.of(
                "game.Client", """
                        package game;
                        public class Client {
                            public static Client getInstance() { return new Client(); }
                            public String describe() { return "new"; }
                        }
                        """));

        ApiRuleSet rules = ApiRuleSet.parse(new java.io.StringReader("""
                {
                  "from": "searge", "to": "modern",
                  "methods": [
                    { "owner": "game/Client", "name": "getClient", "desc": "()Lgame/Client;",
                      "newName": "getInstance", "note": "renamed" }
                  ]
                }
                """), "test");

        byte[] translated = new ApiMigrationTransformer(rules, true).transform("modpkg/Uses",
                mod.get("modpkg.Uses"), TransformContext.of(null, name -> true));

        ByteClassLoader loader = new ByteClassLoader(getClass().getClassLoader())
                .defineAll(newApi)
                .define("modpkg.Uses", translated);

        assertEquals("new", loader.loadClass("modpkg.Uses").getMethod("go").invoke(null));
    }

    @Test
    @DisplayName("a call into deleted API returns a default instead of failing to link")
    void discardsRemovedCall() throws Exception {
        Map<String, byte[]> oldApi = SourceCompiler.compile(Map.of(
                "game.Legacy", """
                        package game;
                        public class Legacy {
                            public static int deletedLater(int a, long b) { return 42; }
                        }
                        """));

        Path stubs = directory.resolve("legacy-api");
        writeClasses(stubs, oldApi);

        Map<String, byte[]> mod = SourceCompiler.compile(Map.of(
                "modpkg.CallsDeleted", """
                        package modpkg;
                        public class CallsDeleted {
                            public static int go() { return game.Legacy.deletedLater(1, 2L) + 7; }
                        }
                        """), List.of(stubs.toString()));

        ApiRuleSet rules = ApiRuleSet.parse(new java.io.StringReader("""
                {
                  "from": "searge", "to": "modern",
                  "removed": [ { "owner": "game/Legacy", "name": "deletedLater", "note": "gone in 1.13" } ]
                }
                """), "test");

        byte[] translated = new ApiMigrationTransformer(rules, true).transform("modpkg/CallsDeleted",
                mod.get("modpkg.CallsDeleted"), TransformContext.of(null, name -> true));

        // game.Legacy is deliberately absent from this loader.
        ByteClassLoader loader = new ByteClassLoader(getClass().getClassLoader())
                .define("modpkg.CallsDeleted", translated);

        assertEquals(7, loader.loadClass("modpkg.CallsDeleted").getMethod("go").invoke(null),
                "the deleted call should contribute a zero, and the surrounding code should still run");
    }

    @Test
    @DisplayName("without the option, a call into deleted API is left alone to fail honestly")
    void keepsRemovedCallWhenStubbingIsOff() throws Exception {
        Map<String, byte[]> oldApi = SourceCompiler.compile(Map.of(
                "game.Legacy2", """
                        package game;
                        public class Legacy2 { public static int gone() { return 1; } }
                        """));

        Path stubs = directory.resolve("legacy-api-2");
        writeClasses(stubs, oldApi);

        Map<String, byte[]> mod = SourceCompiler.compile(Map.of(
                "modpkg.Strict", """
                        package modpkg;
                        public class Strict { public static int go() { return game.Legacy2.gone(); } }
                        """), List.of(stubs.toString()));

        ApiRuleSet rules = ApiRuleSet.parse(new java.io.StringReader("""
                { "from": "searge", "to": "modern",
                  "removed": [ { "owner": "game/Legacy2", "name": "gone" } ] }
                """), "test");

        byte[] untouched = new ApiMigrationTransformer(rules, false).transform("modpkg/Strict",
                mod.get("modpkg.Strict"), TransformContext.of(null, name -> true));

        assertEquals(mod.get("modpkg.Strict").length, untouched.length,
                "with removal disabled the class should come back unchanged");
    }

    @Test
    @DisplayName("the bundled rule packs load and cover the eras they claim")
    void bundledPacksLoad() {
        ApiRuleSet searge = ApiRuleSet.forEras(Era.SEARGE, Era.MODERN, null);

        assertFalse(searge.isEmpty(), "the searge-to-modern route should pick up bundled packs");
        assertNotNull(searge.findMethod("net/minecraft/client/Minecraft", "getMinecraft",
                "()Lnet/minecraft/client/Minecraft;"));
        assertEquals("net/minecraft/world/level/Level",
                searge.classMappings().mapClass("net/minecraft/world/World"),
                "the 1.17 package move should be part of the composed route");
    }

    @Test
    @DisplayName("no rules apply when a mod already matches the running version")
    void noRulesForSameEra() {
        assertTrue(ApiRuleSet.forEras(Era.MODERN, Era.MODERN, null).isEmpty());
        assertTrue(ApiRuleSet.forEras(Era.CURRENT, Era.SEARGE, null).isEmpty(),
                "translation only ever runs forwards");
    }

    // ------------------------------------------------------- phantom classes

    @Test
    @DisplayName("a class that no longer exists is stood in for, with the shape the mod expects")
    void generatesPhantomClass() throws Exception {
        Map<String, byte[]> oldApi = SourceCompiler.compile(Map.of(
                "game.Vanished", """
                        package game;
                        public class Vanished {
                            public int value;
                            public Vanished() { }
                            public String describe() { return "here"; }
                            public static Vanished create() { return new Vanished(); }
                        }
                        """));

        Path stubs = directory.resolve("vanished-api");
        writeClasses(stubs, oldApi);

        Map<String, byte[]> mod = SourceCompiler.compile(Map.of(
                "modpkg.NeedsVanished", """
                        package modpkg;
                        public class NeedsVanished {
                            public static String go() {
                                game.Vanished v = game.Vanished.create();
                                return String.valueOf(v.describe());
                            }
                        }
                        """), List.of(stubs.toString()));

        PhantomClasses phantoms = new PhantomClasses();
        phantoms.observe(mod.get("modpkg.NeedsVanished"), name -> !name.equals("game/Vanished"));

        assertTrue(phantoms.has("game/Vanished"));
        PhantomClasses.Spec spec = phantoms.spec("game/Vanished");
        assertTrue(spec.methods().stream().anyMatch(member -> member.name().equals("describe")));
        assertTrue(spec.methods().stream().anyMatch(member -> member.name().equals("create") && member.isStatic()));

        ByteClassLoader loader = new ByteClassLoader(getClass().getClassLoader())
                .define("game.Vanished", phantoms.generate("game/Vanished"))
                .define("modpkg.NeedsVanished", mod.get("modpkg.NeedsVanished"));

        // create() returns a stand-in instance rather than null, so the chained
        // describe() call still runs; it is the leaf value that comes back empty.
        assertEquals("null", loader.loadClass("modpkg.NeedsVanished").getMethod("go").invoke(null),
                "the mod should run to completion; only the value from the vanished API is missing");
    }

    @Test
    @DisplayName("nothing from the JDK is ever stood in for")
    void neverStandsInForJdkClasses() {
        PhantomClasses phantoms = new PhantomClasses();
        Map<String, byte[]> mod = SourceCompiler.compile(Map.of(
                "modpkg.UsesJdk", """
                        package modpkg;
                        public class UsesJdk {
                            public static int go() { return java.util.List.of(1, 2).size(); }
                        }
                        """));

        // A predicate that claims everything is missing, to prove the guard holds.
        phantoms.observe(mod.get("modpkg.UsesJdk"), name -> false);

        assertNull(phantoms.spec("java/util/List"));
        assertFalse(phantoms.specs().keySet().stream().anyMatch(name -> name.startsWith("java/")));
    }

    // ---------------------------------------------------------------- plumbing

    @Test
    @DisplayName("a pipeline applies its transformers in order and survives one of them failing")
    void pipelineIsResilient() {
        byte[] input = { 1, 2, 3 };

        TransformPipeline pipeline = TransformPipeline.of(List.of(
                new studios.milkdromeda.octo.transform.Transformer() {
                    @Override
                    public String name() {
                        return "explodes";
                    }

                    @Override
                    public byte[] transform(String className, byte[] bytes, TransformContext context) {
                        throw new IllegalStateException("boom");
                    }
                },
                new studios.milkdromeda.octo.transform.Transformer() {
                    @Override
                    public String name() {
                        return "appends";
                    }

                    @Override
                    public byte[] transform(String className, byte[] bytes, TransformContext context) {
                        byte[] out = java.util.Arrays.copyOf(bytes, bytes.length + 1);
                        out[bytes.length] = 4;
                        return out;
                    }
                }));

        byte[] result = pipeline.apply("some/Class", input, TransformContext.of(null, name -> true));

        assertEquals(4, result.length, "the failing transformer should be skipped, not fatal");
    }

    private static void writeClasses(Path directory, Map<String, byte[]> classes) throws IOException {
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            Path file = directory.resolve(entry.getKey().replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
    }
}
