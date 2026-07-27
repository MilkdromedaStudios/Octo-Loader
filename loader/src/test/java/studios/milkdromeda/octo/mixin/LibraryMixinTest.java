package studios.milkdromeda.octo.mixin;

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
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.testkit.JarBuilder;
import studios.milkdromeda.octo.testkit.SourceCompiler;

/**
 * The crash that came from a library living outside the loader.
 *
 * <p>Fabric API's dimension module adds an interface to DataFixerUpper's
 * {@code TaggedChoice} with a mixin, and a Minecraft class casts to it. Octo
 * used to own only the game jars, so the library was loaded by the class loader
 * that started Octo: the mixin reached one copy of the class and the cast was
 * made against another, and the game died in a static initialiser before its
 * first frame with a {@code ClassCastException} between two identical names.
 *
 * <p>This is that, in miniature. It has a test class to itself because mixin's
 * environment is static for the life of the JVM, and a config registered here
 * would still be registered in the next class's launch.
 */
class LibraryMixinTest {
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
    }

    @Test
    @DisplayName("a mixin can add an interface to a library class, and the game can cast to it")
    void aMixinReachesALibraryTheGameShares() throws Exception {
        Path gameJar = buildGameJar();
        Path libraryJar = buildLibraryJar();
        buildLibraryMixinMod(libraryJar);

        LaunchResult result = new OctoLauncher(LaunchContext.builder(gameDir)
                .modDir(modsDir)
                .gameJar(gameJar)
                .libraryJar(libraryJar)
                .side(Side.CLIENT)
                .minecraftVersion("1.20.1")
                .build()).load();

        assertNotNull(result.mod("libmixinmod"), "the mod should have loaded: " + describeFailures(result));
        assertTrue(result.failed().isEmpty(), "nothing should have failed: " + describeFailures(result));

        Class<?> mod = Class.forName("com.example.libmod.LibraryMixinMod", false, result.classLoader());

        // This is the crash, exactly: Fabric API's dimension module adds an
        // interface to DataFixerUpper's TaggedChoice and a Minecraft class casts
        // to it. With the library outside the loader, the mixin never reaches
        // the copy the cast is made against and it is a ClassCastException
        // between two classes with the same name.
        assertEquals("extended", mod.getField("viaInterface").get(null),
                "the cast to the mixin-added interface should have succeeded");
    }

    /** A stand-in for DataFixerUpper: on the class path, not a mod, not the game. */
    private Path buildLibraryJar() throws IOException {
        return new JarBuilder()
                .classes(SourceCompiler.compile(Map.of("com.example.lib.Choice", """
                        package com.example.lib;
                        public class Choice {
                            public String name() { return "choice"; }
                        }
                        """)))
                .writeTo(gameDir.resolve("libraries/choice.jar"));
    }

    private void buildLibraryMixinMod(Path libraryJar) throws IOException {
        Map<String, byte[]> classes = SourceCompiler.compile(Map.of(
                "com.example.libmod.ChoiceExtension", """
                        package com.example.libmod;

                        public interface ChoiceExtension {
                            String octo$extra();
                        }
                        """,
                "com.example.libmixin.ChoiceMixin", """
                        package com.example.libmixin;

                        import com.example.libmod.ChoiceExtension;
                        import org.spongepowered.asm.mixin.Mixin;

                        @Mixin(targets = "com.example.lib.Choice", remap = false)
                        public abstract class ChoiceMixin implements ChoiceExtension {
                            @Override
                            public String octo$extra() {
                                return "extended";
                            }
                        }
                        """,
                "com.example.libmod.LibraryMixinMod", """
                        package com.example.libmod;

                        import com.example.lib.Choice;
                        import net.fabricmc.api.ModInitializer;

                        public class LibraryMixinMod implements ModInitializer {
                            public static String viaInterface;

                            @Override
                            public void onInitialize() {
                                viaInterface = ((ChoiceExtension) (Object) new Choice()).octo$extra();
                            }
                        }
                        """), List.of(libraryJar.toString()));

        new JarBuilder()
                .classes(classes)
                .file("libmixin.mixins.json", """
                        {
                          "required": true,
                          "minVersion": "0.8",
                          "package": "com.example.libmixin",
                          "compatibilityLevel": "JAVA_17",
                          "mixins": ["ChoiceMixin"],
                          "injectors": { "defaultRequire": 1 }
                        }
                        """)
                .file("fabric.mod.json", """
                        {
                          "schemaVersion": 1,
                          "id": "libmixinmod",
                          "version": "1.0.0",
                          "entrypoints": { "main": ["com.example.libmod.LibraryMixinMod"] },
                          "mixins": ["libmixin.mixins.json"]
                        }
                        """)
                .writeTo(modsDir.resolve("libmixin-mod.jar"));
    }

    private static String describeFailures(LaunchResult result) {
        StringBuilder out = new StringBuilder();
        result.failed().forEach(mod -> out.append(mod.id()).append(": ").append(mod.failure()).append(' '));
        return out.toString();
    }

    /** A minimal stand-in for Minecraft: enough for the loader to recognise a game. */
    private Path buildGameJar() throws IOException {
        return new JarBuilder()
                .classes(SourceCompiler.compile(Map.of("net.minecraft.client.Minecraft", """
                        package net.minecraft.client;
                        public class Minecraft {
                            private static final Minecraft INSTANCE = new Minecraft();
                            public static Minecraft getInstance() { return INSTANCE; }
                        }
                        """)))
                .file("version.json", "{\"id\": \"1.20.1\", \"name\": \"1.20.1\"}")
                .writeTo(gameDir.resolve("game/minecraft-1.20.1.jar"));
    }
}
