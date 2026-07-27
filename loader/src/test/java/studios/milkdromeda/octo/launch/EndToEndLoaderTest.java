package studios.milkdromeda.octo.launch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;
import studios.milkdromeda.octo.compat.Era;
import studios.milkdromeda.octo.compat.mapping.MappingRegistry;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.testkit.JarBuilder;
import studios.milkdromeda.octo.testkit.SourceCompiler;

/**
 * The whole loader, running mods from every ecosystem at once.
 *
 * <p>A synthetic Minecraft jar stands in for the real one — a class named
 * {@code net.minecraft.client.Minecraft} with today's API, and a {@code Main} to
 * hand over to. Six mods go in the folder: Fabric, Quilt, Forge, NeoForge,
 * LiteLoader, and one built for Minecraft 1.7.10 against an API that no longer
 * exists. All six are expected to run, in one JVM, from one mods folder.
 */
class EndToEndLoaderTest {
    @TempDir
    Path gameDir;

    private Path modsDir;

    @BeforeEach
    void setUp() throws IOException {
        modsDir = gameDir.resolve("mods");
        Files.createDirectories(modsDir);
    }

    @AfterEach
    void tearDown() {
        OctoRuntime.reset();
        ForgeBuses.reset();
        MappingRegistry.reset();
    }

    @Test
    @DisplayName("six mods, four ecosystems, fifteen years apart, one launch")
    void loadsEverything() throws Exception {
        Path gameJar = buildGameJar();
        buildFabricMod();
        buildQuiltMod();
        buildForgeMod();
        buildNeoForgeMod();
        buildLiteMod();
        buildAncientMod();

        LaunchResult result = launch(gameJar);

        assertEquals(6, result.loaded().size(),
                "every mod should load; failures: " + result.failed());
        assertTrue(result.failed().isEmpty(), "no mod should have failed: " + result.failed());

        assertRan(result, "fabricmod", "com.example.fabric.FabricMod");
        assertRan(result, "quiltmod", "com.example.quilt.QuiltMod");
        assertRan(result, "forgemod", "com.example.forge.ForgeMod");
        assertRan(result, "neomod", "com.example.neo.NeoMod");
        assertRan(result, "litemod", "com.example.lite.LiteModExample");
        assertRan(result, "ancientmod", "com.example.ancient.AncientMod");
    }

    @Test
    @DisplayName("a mod built for 1.7.10 is translated and calls into today's API successfully")
    void translatesAnAncientMod() throws Exception {
        Path gameJar = buildGameJar();
        buildAncientMod();

        LaunchResult result = launch(gameJar);
        LoadedMod ancient = result.mod("ancientmod");

        assertNotNull(ancient, "the 1.7.10 mod should have loaded");
        assertEquals(Era.SEARGE, ancient.era(), "mcversion 1.7.10 puts it in the Searge era");
        assertEquals(ModFormat.LEGACY_FORGE, ancient.format());

        Class<?> modClass = Class.forName("com.example.ancient.AncientMod", false, result.classLoader());

        assertTrue(modClass.getField("ran").getBoolean(null),
                "its @Mod.EventHandler preInit method should have been called");
        assertEquals("1.20.1", modClass.getField("versionSeen").get(null),
                "Minecraft.getMinecraft() should have been rewritten to getInstance() and returned the real game");
        assertEquals(0, modClass.getField("goneApiResult").getInt(null),
                "the call into deleted API should have returned a default rather than failing to link");

        assertFalse(ancient.appliedFixes().isEmpty(), "the translation should be recorded on the mod");
        assertTrue(ancient.appliedFixes().stream().anyMatch(fix -> fix.contains("getMinecraft")),
                "the applied fixes should name the rewritten call: " + ancient.appliedFixes());
    }

    @Test
    @DisplayName("a Forge mod can see a Fabric mod, because there is only one mod list")
    void modsFromDifferentEcosystemsSeeEachOther() throws Exception {
        Path gameJar = buildGameJar();
        buildFabricMod();
        buildForgeMod();

        LaunchResult result = launch(gameJar);
        Class<?> forgeMod = Class.forName("com.example.forge.ForgeMod", false, result.classLoader());

        assertTrue(forgeMod.getField("sawFabricMod").getBoolean(null),
                "ModList.get().isLoaded(\"fabricmod\") should be true for a Fabric mod");

        Class<?> fabricMod = Class.forName("com.example.fabric.FabricMod", false, result.classLoader());
        assertTrue(fabricMod.getField("sawForgeMod").getBoolean(null),
                "FabricLoader.getInstance().isModLoaded(\"forgemod\") should be true for a Forge mod");

        assertEquals("hello from fabric", OctoRuntime.get().shareGet("fabricmod:greeting"),
                "the object share should be common to both ecosystems");
    }

    @Test
    @DisplayName("a mod declaring a Minecraft version this is not gets a warning, not a refusal")
    void versionMismatchIsNotFatal() throws Exception {
        Path gameJar = buildGameJar();
        buildAncientMod();

        LaunchResult result = launch(gameJar);

        assertNotNull(result.mod("ancientmod"));
        assertTrue(result.resolution().problems().stream()
                        .anyMatch(problem -> !problem.fatal() && problem.modId().equals("ancientmod")),
                "the mismatch should be reported: " + result.resolution().problems());
        assertTrue(result.resolution().fatalProblems().isEmpty(),
                "nothing should have been fatal: " + result.resolution().fatalProblems());
    }

    @Test
    @DisplayName("a mod whose dependency is not installed stops the launch and names it")
    void anUnsatisfiableDependencyStopsTheLaunch() throws Exception {
        Path gameJar = buildGameJar();

        new JarBuilder().file("fabric.mod.json", """
                { "schemaVersion": 1, "id": "needsmissing", "version": "1.0.0",
                  "depends": { "notinstalled": "*" } }
                """).writeTo(modsDir.resolve("needsmissing.jar"));

        ModLoadingException refusal = assertThrows(ModLoadingException.class, () -> launch(gameJar));

        assertTrue(refusal.getMessage().contains("needsmissing"),
                "the refusal should name the mod: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("notinstalled"),
                "and what it was missing: " + refusal.getMessage());
    }

    @Test
    @DisplayName("with --lenient, that mod is skipped and the game still starts")
    void lenientModeSkipsAnUnsatisfiableDependency() throws Exception {
        Path gameJar = buildGameJar();

        new JarBuilder().file("fabric.mod.json", """
                { "schemaVersion": 1, "id": "needsmissing", "version": "1.0.0",
                  "depends": { "notinstalled": "*" } }
                """).writeTo(modsDir.resolve("needsmissing.jar"));

        LaunchResult result = launchLeniently(gameJar);

        assertTrue(result.loaded().isEmpty(), "the mod should not have loaded");
        assertEquals(1, result.resolution().fatalProblems().size());
        assertTrue(result.resolution().fatalProblems().get(0).message().contains("notinstalled"));
    }

    @Test
    @DisplayName("a mod that throws an Error stops the launch rather than disappearing")
    void oneBrokenModStopsTheLaunch() throws Exception {
        Path gameJar = buildGameJar();
        buildFabricMod();
        buildExplodingMod();

        ModLoadingException refusal = assertThrows(ModLoadingException.class, () -> launch(gameJar));

        assertTrue(refusal.getMessage().contains("explodingmod"),
                "the refusal should name the mod that failed: " + refusal.getMessage());
    }

    @Test
    @DisplayName("with --lenient, a mod that throws an Error is skipped and every other mod still loads")
    void lenientModeSkipsOneBrokenMod() throws Exception {
        Path gameJar = buildGameJar();
        buildFabricMod();
        buildExplodingMod();

        LaunchResult result = launchLeniently(gameJar);

        assertEquals(1, result.failed().size(), "only the broken mod should have failed: " + result.failed());
        assertEquals("explodingmod", result.failed().get(0).id());
        assertTrue(result.failed().get(0).failure() instanceof AssertionError,
                "the mod's own AssertionError should be what was recorded, unwrapped: "
                        + result.failed().get(0).failure());

        assertRan(result, "fabricmod", "com.example.fabric.FabricMod");
    }

    @Test
    @DisplayName("a jar whose metadata will not parse stops the launch instead of being skipped")
    void brokenMetadataStopsTheLaunch() throws Exception {
        Path gameJar = buildGameJar();
        buildFabricMod();
        new JarBuilder().file("fabric.mod.json", "{ this is not json")
                .writeTo(modsDir.resolve("corrupt.jar"));

        ModLoadingException refusal = assertThrows(ModLoadingException.class, () -> launch(gameJar));

        assertTrue(refusal.getMessage().contains("corrupt.jar"),
                "the refusal should name the file: " + refusal.getMessage());
    }

    @Test
    @DisplayName("a mod calling loader API Octo never implemented loads instead of killing the launch")
    void aCallIntoUnimplementedLoaderApiIsCovered() throws Exception {
        Path gameJar = buildGameJar();
        buildApiGapMod();

        LaunchResult result = launch(gameJar);

        assertNotNull(result.mod("gapmod"), "the mod should have loaded: " + result.failed());
        assertTrue(result.failed().isEmpty(), "nothing should have failed: " + result.failed());

        Class<?> type = Class.forName("com.example.gap.GapMod", false, result.classLoader());
        assertTrue(type.getField("ran").getBoolean(null), "its initialiser should have run to the end");
        assertNull(type.getField("answer").get(null), "the unimplemented call should have produced null");

        assertTrue(result.mod("gapmod").appliedFixes().stream()
                        .anyMatch(fix -> fix.contains("neverImplementedByOcto")),
                "the gap should be recorded on the mod: " + result.mod("gapmod").appliedFixes());
    }

    /**
     * A mod whose initialiser calls a method Octo does not have.
     *
     * <p>Assembled rather than compiled, because javac will not emit a call to a
     * method that does not exist — which is the entire situation being tested.
     * This is what Create does from a mixin plugin, and before the loader
     * covered it, the {@code NoSuchMethodError} ended the launch.
     */
    private void buildApiGapMod() throws IOException {
        String internalName = "com/example/gap/GapMod";
        String modList = "net/neoforged/fml/ModList";
        org.objectweb.asm.ClassWriter writer =
                new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);

        writer.visit(org.objectweb.asm.Opcodes.V17, org.objectweb.asm.Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", new String[] { "net/fabricmc/api/ModInitializer" });
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "ran", "Z", null, null).visitEnd();
        writer.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC,
                "answer", "Ljava/lang/Object;", null, null).visitEnd();

        org.objectweb.asm.MethodVisitor constructor =
                writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        constructor.visitMaxs(0, 0);
        constructor.visitEnd();

        org.objectweb.asm.MethodVisitor initialize =
                writer.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
        initialize.visitCode();
        initialize.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC, modList, "get", "()L" + modList + ";", false);
        initialize.visitLdcInsn("somemod");
        initialize.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, modList, "neverImplementedByOcto",
                "(Ljava/lang/String;)Ljava/lang/Object;", false);
        initialize.visitFieldInsn(org.objectweb.asm.Opcodes.PUTSTATIC, internalName, "answer", "Ljava/lang/Object;");
        initialize.visitInsn(org.objectweb.asm.Opcodes.ICONST_1);
        initialize.visitFieldInsn(org.objectweb.asm.Opcodes.PUTSTATIC, internalName, "ran", "Z");
        initialize.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        initialize.visitMaxs(0, 0);
        initialize.visitEnd();
        writer.visitEnd();

        new JarBuilder()
                .classes(Map.of("com.example.gap.GapMod", writer.toByteArray()))
                .file("fabric.mod.json", """
                        {
                          "schemaVersion": 1,
                          "id": "gapmod",
                          "version": "1.0.0",
                          "entrypoints": { "main": ["com.example.gap.GapMod"] }
                        }
                        """)
                .writeTo(modsDir.resolve("gap-sample.jar"));
    }

    @Test
    @DisplayName("an empty mods folder is not a failure")
    void anEmptyModsFolderStartsCleanly() throws Exception {
        LaunchResult result = launch(buildGameJar());

        assertTrue(result.loaded().isEmpty());
        assertTrue(result.failed().isEmpty());
    }

    @Test
    @DisplayName("the game's own main class is invoked once every mod is ready")
    void handsOverToTheGame() throws Exception {
        Path gameJar = buildGameJar();
        buildFabricMod();

        LaunchContext context = context(gameJar).mainClass("net.minecraft.client.main.Main")
                .launchArguments(List.of("--username", "Tester"))
                .build();

        LaunchResult result = new OctoLauncher(context).launch();

        // The game runs inside the loader's own class loader, so its state has to
        // be read back through that loader rather than the test's.
        Class<?> main = Class.forName("net.minecraft.client.main.Main", false, result.classLoader());

        assertTrue(main.getField("started").getBoolean(null), "the game's main method should have run");
        assertArrayEquals(new String[] { "--username", "Tester" }, (String[]) main.getField("arguments").get(null),
                "the launcher's arguments should be passed through untouched");
        assertTrue(result.mod("fabricmod").instances().size() > 0,
                "the mod should have been constructed before the game started");
    }

    // ------------------------------------------------------------- fixtures

    private LaunchResult launch(Path gameJar) {
        return new OctoLauncher(context(gameJar).build()).load();
    }

    /** The old behaviour: skip what cannot be loaded and start the game anyway. */
    private LaunchResult launchLeniently(Path gameJar) {
        return new OctoLauncher(context(gameJar)
                .compat(new LaunchContext.CompatOptions().strict(false))
                .build()).load();
    }

    private LaunchContext.Builder context(Path gameJar) {
        return LaunchContext.builder(gameDir)
                .modDir(modsDir)
                .gameJar(gameJar)
                .side(Side.CLIENT)
                .minecraftVersion("1.20.1");
    }

    private void assertRan(LaunchResult result, String modId, String className) throws Exception {
        LoadedMod mod = result.mod(modId);
        assertNotNull(mod, modId + " should have loaded");
        Class<?> type = Class.forName(className, false, result.classLoader());
        assertTrue(type.getField("ran").getBoolean(null), modId + " should have run its entrypoint");
    }

    /** A stand-in for Minecraft: today's API, and a main class to hand over to. */
    private Path buildGameJar() throws IOException {
        Map<String, byte[]> classes = SourceCompiler.compile(Map.of(
                "net.minecraft.client.Minecraft", """
                        package net.minecraft.client;
                        public class Minecraft {
                            private static final Minecraft INSTANCE = new Minecraft();
                            public static Minecraft getInstance() { return INSTANCE; }
                            public String getGameVersion() { return "1.20.1"; }
                        }
                        """,
                "net.minecraft.client.main.Main", """
                        package net.minecraft.client.main;
                        public class Main {
                            public static boolean started;
                            public static String[] arguments;
                            public static void main(String[] args) { started = true; arguments = args; }
                        }
                        """));

        return new JarBuilder()
                .classes(classes)
                .file("version.json", "{\"id\": \"1.20.1\", \"name\": \"1.20.1\"}")
                .writeTo(gameDir.resolve("game/minecraft-1.20.1.jar"));
    }

    private void buildFabricMod() throws IOException {
        Map<String, byte[]> classes = SourceCompiler.compile(Map.of(
                "com.example.fabric.FabricMod", """
                        package com.example.fabric;

                        import net.fabricmc.api.ModInitializer;
                        import net.fabricmc.loader.api.FabricLoader;

                        public class FabricMod implements ModInitializer {
                            public static boolean ran;
                            public static boolean sawForgeMod;

                            @Override
                            public void onInitialize() {
                                ran = true;
                                sawForgeMod = FabricLoader.getInstance().isModLoaded("forgemod");
                                FabricLoader.getInstance().getObjectShare().put("fabricmod:greeting", "hello from fabric");
                            }
                        }
                        """));

        new JarBuilder().classes(classes).file("fabric.mod.json", """
                {
                  "schemaVersion": 1,
                  "id": "fabricmod",
                  "version": "1.0.0",
                  "name": "Fabric Sample",
                  "environment": "*",
                  "entrypoints": { "main": ["com.example.fabric.FabricMod"] },
                  "depends": { "minecraft": ">=1.20 <1.21" }
                }
                """).writeTo(modsDir.resolve("fabric-sample.jar"));
    }

    /**
     * A mod that fails the way real ones do.
     *
     * <p>{@code AssertionError} is neither an {@code Exception} nor a
     * {@code LinkageError}, which is exactly why an unapplied mixin accessor —
     * whose generated body is {@code throw new AssertionError()} — used to abort
     * the entire launch rather than costing the player one mod.
     */
    private void buildExplodingMod() throws IOException {
        Map<String, byte[]> classes = SourceCompiler.compile(Map.of(
                "com.example.bad.ExplodingMod", """
                        package com.example.bad;

                        import net.fabricmc.api.ModInitializer;

                        public class ExplodingMod implements ModInitializer {
                            @Override
                            public void onInitialize() {
                                throw new AssertionError();
                            }
                        }
                        """));

        new JarBuilder().classes(classes).file("fabric.mod.json", """
                {
                  "schemaVersion": 1,
                  "id": "explodingmod",
                  "version": "1.0.0",
                  "entrypoints": { "main": ["com.example.bad.ExplodingMod"] }
                }
                """).writeTo(modsDir.resolve("exploding-sample.jar"));
    }

    private void buildQuiltMod() throws IOException {
        Map<String, byte[]> classes = SourceCompiler.compile(Map.of(
                "com.example.quilt.QuiltMod", """
                        package com.example.quilt;

                        import org.quiltmc.loader.api.ModContainer;
                        import org.quiltmc.loader.api.QuiltLoader;
                        import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;

                        public class QuiltMod implements ModInitializer {
                            public static boolean ran;
                            public static String ownId;

                            @Override
                            public void onInitialize(ModContainer mod) {
                                ran = true;
                                ownId = mod.metadata().id();
                                QuiltLoader.isModLoaded("fabricmod");
                            }
                        }
                        """));

        new JarBuilder().classes(classes).file("quilt.mod.json", """
                {
                  "schema_version": 1,
                  "quilt_loader": {
                    "group": "com.example",
                    "id": "quiltmod",
                    "version": "1.0.0",
                    "metadata": { "name": "Quilt Sample" },
                    "entrypoints": { "main": ["com.example.quilt.QuiltMod"] },
                    "depends": [{ "id": "minecraft", "versions": ">=1.20" }]
                  },
                  "minecraft": { "environment": "*" }
                }
                """).writeTo(modsDir.resolve("quilt-sample.jar"));
    }

    private void buildForgeMod() throws IOException {
        Map<String, byte[]> classes = SourceCompiler.compile(Map.of(
                "com.example.forge.ForgeMod", """
                        package com.example.forge;

                        import net.minecraftforge.eventbus.api.IEventBus;
                        import net.minecraftforge.fml.ModList;
                        import net.minecraftforge.fml.common.Mod;
                        import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

                        @Mod("forgemod")
                        public class ForgeMod {
                            public static boolean ran;
                            public static boolean sawFabricMod;

                            public ForgeMod(IEventBus modBus) {
                                modBus.addListener(FMLCommonSetupEvent.class, this::onCommonSetup);
                            }

                            private void onCommonSetup(FMLCommonSetupEvent event) {
                                ran = true;
                                sawFabricMod = ModList.get().isLoaded("fabricmod");
                            }
                        }
                        """));

        new JarBuilder().classes(classes).file("META-INF/mods.toml", """
                modLoader="javafml"
                loaderVersion="[47,)"
                license="MIT"
                [[mods]]
                modId="forgemod"
                version="1.0.0"
                displayName="Forge Sample"
                [[dependencies.forgemod]]
                    modId="minecraft"
                    mandatory=true
                    versionRange="[1.20.1,1.21)"
                    ordering="NONE"
                    side="BOTH"
                """).writeTo(modsDir.resolve("forge-sample.jar"));
    }

    private void buildNeoForgeMod() throws IOException {
        Map<String, byte[]> classes = SourceCompiler.compile(Map.of(
                "com.example.neo.NeoMod", """
                        package com.example.neo;

                        import net.neoforged.bus.api.IEventBus;
                        import net.neoforged.bus.api.SubscribeEvent;
                        import net.neoforged.fml.common.Mod;
                        import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

                        @Mod("neomod")
                        public class NeoMod {
                            public static boolean ran;

                            public NeoMod(IEventBus modBus) {
                                modBus.register(this);
                            }

                            @SubscribeEvent
                            public void onCommonSetup(FMLCommonSetupEvent event) {
                                ran = true;
                            }
                        }
                        """));

        new JarBuilder().classes(classes).file("META-INF/neoforge.mods.toml", """
                modLoader="javafml"
                loaderVersion="[21,)"
                license="MIT"
                [[mods]]
                modId="neomod"
                version="1.0.0"
                displayName="NeoForge Sample"
                [[dependencies.neomod]]
                    modId="minecraft"
                    type="required"
                    versionRange="[1.20.1,1.21)"
                """).writeTo(modsDir.resolve("neoforge-sample.jar"));
    }

    private void buildLiteMod() throws IOException {
        Map<String, byte[]> classes = SourceCompiler.compile(Map.of(
                "com.example.lite.LiteModExample", """
                        package com.example.lite;

                        import java.io.File;

                        public class LiteModExample implements com.mumfrey.liteloader.LiteMod {
                            public static boolean ran;

                            @Override public String getName() { return "Lite Sample"; }
                            @Override public String getVersion() { return "1.0.0"; }
                            @Override public void init(File configPath) { ran = true; }
                        }
                        """));

        new JarBuilder().classes(classes).file("litemod.json", """
                { "name": "litemod", "displayName": "Lite Sample", "version": "1.0.0",
                  "mcversion": "1.20.1", "author": "Someone" }
                """).writeTo(modsDir.resolve("lite-sample.litemod"));
    }

    /**
     * A mod as it was written in 2014: {@code mcmod.info}, an {@code @Mod} class
     * with {@code @EventHandler} methods, a call to {@code Minecraft.getMinecraft()},
     * and a call into a class that was deleted years ago. It is compiled against
     * that old API and then never given it again.
     */
    private void buildAncientMod() throws IOException {
        Map<String, byte[]> oldGameApi = SourceCompiler.compile(Map.of(
                "net.minecraft.client.Minecraft", """
                        package net.minecraft.client;
                        public class Minecraft {
                            public static Minecraft getMinecraft() { return new Minecraft(); }
                            public String getGameVersion() { return "1.7.10"; }
                        }
                        """,
                "net.minecraft.util.LongGone", """
                        package net.minecraft.util;
                        public class LongGone {
                            public static int doThing() { return 99; }
                        }
                        """));

        Path oldApiDirectory = gameDir.resolve("old-api");

        for (Map.Entry<String, byte[]> entry : oldGameApi.entrySet()) {
            Path file = oldApiDirectory.resolve(entry.getKey().replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }

        Map<String, byte[]> classes = SourceCompiler.compile(Map.of(
                "com.example.ancient.AncientMod", """
                        package com.example.ancient;

                        import net.minecraft.client.Minecraft;
                        import net.minecraft.util.LongGone;
                        import net.minecraftforge.fml.common.Mod;
                        import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

                        @Mod(modid = "ancientmod", name = "Ancient Mod", version = "1.2.3")
                        public class AncientMod {
                            public static boolean ran;
                            public static String versionSeen;
                            public static int goneApiResult = -1;

                            @Mod.EventHandler
                            public void preInit(FMLPreInitializationEvent event) {
                                ran = true;
                                versionSeen = Minecraft.getMinecraft().getGameVersion();
                                goneApiResult = LongGone.doThing();
                            }
                        }
                        """), List.of(oldApiDirectory.toString()));

        new JarBuilder().classes(classes, "com.example.ancient").file("mcmod.info", """
                [
                  {
                    "modid": "ancientmod",
                    "name": "Ancient Mod",
                    "description": "Written in 2014, loaded today.",
                    "version": "1.2.3",
                    "mcversion": "1.7.10",
                    "authorList": ["Someone"]
                  }
                ]
                """).writeTo(modsDir.resolve("ancient-mod.jar"));
    }
}
