package studios.milkdromeda.octo.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import studios.milkdromeda.octo.compat.mapping.MappingRegistry;
import studios.milkdromeda.octo.launch.LaunchContext;
import studios.milkdromeda.octo.launch.LaunchResult;
import studios.milkdromeda.octo.launch.OctoLauncher;
import studios.milkdromeda.octo.mixin.MixinSupport;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.testkit.JarBuilder;
import studios.milkdromeda.octo.testkit.SourceCompiler;

/**
 * When a client mod's initialiser runs.
 *
 * <p>Fabric runs {@code onInitializeClient} during the game's construction, and
 * mods are written to that: the first line of a great many of them is
 * {@code Minecraft.getInstance()}. Octo used to run every phase before the
 * game's {@code main} was called at all, so that call returned null, the mod
 * threw, and the result was a loader reporting a mod as loaded and a game with
 * no trace of it.
 */
class ClientInitTimingTest {
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
        MixinSupport.reset();
        GameHooks.reset();
    }

    @Test
    @DisplayName("a client mod is initialised while the game is being built, not before")
    void clientInitialisationWaitsForTheGame() throws Exception {
        Path gameJar = buildGameJar();
        buildClientMod();

        LaunchContext context = LaunchContext.builder(gameDir)
                .modDir(modsDir)
                .gameJar(gameJar)
                .side(Side.CLIENT)
                .minecraftVersion("1.20.1")
                .mainClass("net.minecraft.client.main.Main")
                .launchArguments(List.of("--username", "Tester"))
                .build();

        LaunchResult result = new OctoLauncher(context).launch();

        assertTrue(result.failed().isEmpty(), "the mod should not have failed: " + result.failed());

        Class<?> mod = Class.forName("com.example.client.ClientMod", false, result.classLoader());

        assertTrue(mod.getField("ran").getBoolean(null), "the client entrypoint should have run");
        assertNotNull(mod.getField("gameSeen").get(null),
                "Minecraft.getInstance() should have returned the game, not null");

        // The proof of ordering: the game records how far through its own start-up
        // it was when the mod's initialiser ran.
        assertEquals("constructing", mod.getField("stageSeen").get(null),
                "the mod should have been initialised during the game's construction");

        Class<?> game = Class.forName("net.minecraft.client.Minecraft", false, result.classLoader());
        assertEquals(mod.getField("gameSeen").get(null), game.getField("INSTANCE").get(null),
                "and it should have been handed the real game instance");
    }

    /** A stand-in whose constructor is the moment the hook attaches to. */
    private Path buildGameJar() throws IOException {
        Map<String, byte[]> classes = SourceCompiler.compile(gameSources());

        // The mod is compiled against the game, as a real one is.
        Path directory = gameDir.resolve("game-api");

        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            Path file = directory.resolve(entry.getKey().replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }

        return new JarBuilder()
                .classes(classes)
                .file("version.json", "{\"id\": \"1.20.1\", \"name\": \"1.20.1\"}")
                .writeTo(gameDir.resolve("game/minecraft-1.20.1.jar"));
    }

    private static Map<String, String> gameSources() {
        return Map.of(
                "net.minecraft.client.Minecraft", """
                        package net.minecraft.client;
                        public class Minecraft {
                            public static Minecraft INSTANCE;
                            public static String stage = "not started";

                            public Minecraft() {
                                INSTANCE = this;
                                stage = "constructing";
                            }

                            public static Minecraft getInstance() { return INSTANCE; }
                        }
                        """,
                "net.minecraft.client.main.Main", """
                        package net.minecraft.client.main;

                        import net.minecraft.client.Minecraft;

                        public class Main {
                            public static void main(String[] args) {
                                new Minecraft();
                                Minecraft.stage = "running";
                            }
                        }
                        """);
    }

    private void buildClientMod() throws IOException {
        Map<String, byte[]> classes = SourceCompiler.compile(Map.of(
                "com.example.client.ClientMod", """
                        package com.example.client;

                        import net.fabricmc.api.ClientModInitializer;
                        import net.minecraft.client.Minecraft;

                        public class ClientMod implements ClientModInitializer {
                            public static boolean ran;
                            public static Object gameSeen;
                            public static String stageSeen;

                            @Override
                            public void onInitializeClient() {
                                ran = true;
                                gameSeen = Minecraft.getInstance();
                                stageSeen = Minecraft.stage;
                            }
                        }
                        """), List.of(gameDir.resolve("game-api").toString()));

        new JarBuilder().classes(classes).file("fabric.mod.json", """
                {
                  "schemaVersion": 1,
                  "id": "clientmod",
                  "version": "1.0.0",
                  "environment": "client",
                  "entrypoints": { "client": ["com.example.client.ClientMod"] }
                }
                """).writeTo(modsDir.resolve("client-sample.jar"));
    }
}
