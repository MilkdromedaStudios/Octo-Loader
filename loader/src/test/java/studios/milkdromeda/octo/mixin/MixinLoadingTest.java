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
import org.spongepowered.asm.mixin.MixinEnvironment;

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
 * The three things a mixin-based mod needs, none of which Octo used to do.
 *
 * <p>Every failure this reproduces came from one real crash log: a mixin
 * accessor throwing {@code AssertionError} because it was never applied, an
 * {@code IllegalAccessError} on a private game method the mod was compiled
 * against, and {@code cannot inherit from final class} where an access widener
 * should have removed {@code final}.
 *
 * <p>The fixture mirrors how mods are really built: the mod is compiled against
 * a widened view of the game — private members public, final classes open —
 * while the jar the loader is handed contains the restricted original.
 */
class MixinLoadingTest {
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
    @DisplayName("a mod's mixins are applied to the game, and its access widener opens the game up")
    void appliesMixinsAndAccessWideners() throws Exception {
        Path gameJar = buildGameJar();
        buildMixinMod();

        LaunchResult result = launch(gameJar);

        assertNotNull(result.mod("mixinmod"), "the mod should have loaded: " + result.failed());
        assertTrue(result.failed().isEmpty(), "nothing should have failed: " + describeFailures(result));

        Class<?> mod = Class.forName("com.example.mod.MixinMod", false, result.classLoader());

        // @Inject: the mixin cancels the vanilla method and returns its own value.
        assertEquals("modded", mod.getField("greeting").get(null),
                "the @Inject should have replaced the game's return value");

        // @Invoker: the accessor mixin is the exact shape whose unapplied form
        // throws the AssertionError that used to abort the whole launch.
        assertEquals("hidden", mod.getField("secretViaInvoker").get(null),
                "the @Invoker should have reached the game's private method");

        // Access widener, "accessible": a direct call to a private game method.
        assertEquals("hidden", mod.getField("secretViaWidener").get(null),
                "the access widener should have made the private method callable");

        // Access widener, "extendable": subclassing a final game class.
        assertTrue((Boolean) mod.getField("extendedFinalClass").get(null),
                "the access widener should have removed final from the game class");
    }

    @Test
    @DisplayName("a mixin's inner class survives being moved into the class it was mixed into")
    void definesTheInnerClassesMixinInvents() throws Exception {
        Path gameJar = buildGameJar();
        buildMixinMod();

        LaunchResult result = launch(gameJar);

        Class<?> mod = Class.forName("com.example.mod.MixinMod", false, result.classLoader());

        // The mixin's anonymous class cannot stay in the mod's package, because
        // the method using it is copied into a game class in another one. Mixin
        // renames it, hands the game class the new name, and keeps the bytes;
        // nothing on the class path has them. Answering "no such class" here is
        // what left Fabric API's mixin applied and its target unloadable — and
        // because that target was one the game loads while filling a registry,
        // the crash arrived much later with none of this in it.
        assertEquals("modded tooltip", mod.getField("tooltipViaInnerClass").get(null),
                "the class mixin invented for its inner class should have been defined");
    }

    @Test
    @DisplayName("mixin's Java ceiling is raised to what the running JVM can actually do")
    void raisesTheCompatibilityCeiling() throws Exception {
        Path gameJar = buildGameJar();
        buildMixinMod();

        launch(gameJar);

        // Mixin ships pinned at JAVA_13 and leaves it to the host to raise. Every
        // mixin config written in the last few years asks for JAVA_17 or later,
        // so leaving it alone rejects all of them.
        assertTrue(MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED
                        .isAtLeast(MixinEnvironment.CompatibilityLevel.JAVA_17),
                "the ceiling should have been raised past mixin's default, but is "
                        + MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED);
    }

    private static String describeFailures(LaunchResult result) {
        StringBuilder out = new StringBuilder();
        result.failed().forEach(mod -> out.append(mod.id()).append(": ").append(mod.failure()).append(' '));
        return out.toString();
    }

    private LaunchResult launch(Path gameJar) {
        LaunchContext context = LaunchContext.builder(gameDir)
                .modDir(modsDir)
                .gameJar(gameJar)
                .side(Side.CLIENT)
                .minecraftVersion("1.20.1")
                .build();

        return new OctoLauncher(context).load();
    }

    // ------------------------------------------------------------- fixtures

    /** The game as it ships: a private method and a final class. */
    private Path buildGameJar() throws IOException {
        return new JarBuilder()
                .classes(SourceCompiler.compile(gameSources(false)))
                .file("version.json", "{\"id\": \"1.20.1\", \"name\": \"1.20.1\"}")
                .writeTo(gameDir.resolve("game/minecraft-1.20.1.jar"));
    }

    /** The game as a mod author sees it, once a widener has been applied at build time. */
    private Path buildWidenedGameClasses() throws IOException {
        Path directory = gameDir.resolve("widened");
        Files.createDirectories(directory);

        for (Map.Entry<String, byte[]> entry : SourceCompiler.compile(gameSources(true)).entrySet()) {
            Path file = directory.resolve(entry.getKey().replace('.', '/') + ".class");
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }

        return directory;
    }

    private static Map<String, String> gameSources(boolean widened) {
        return Map.of(
                "net.minecraft.client.Minecraft", """
                        package net.minecraft.client;
                        public class Minecraft {
                            private static final Minecraft INSTANCE = new Minecraft();
                            public static Minecraft getInstance() { return INSTANCE; }
                            public String greeting() { return "vanilla"; }
                            public String tooltip() { return "vanilla tooltip"; }
                            %s String secret() { return "hidden"; }
                        }
                        """.formatted(widened ? "public" : "private"),
                "net.minecraft.client.gui.Screen", """
                        package net.minecraft.client.gui;
                        public %s class Screen {
                            public String title() { return "screen"; }
                        }
                        """.formatted(widened ? "" : "final"));
    }

    /**
     * One mod that does all four things: injects, invokes a private method
     * through an accessor, calls one directly through a widener, and subclasses
     * a class the game declares final.
     */
    private void buildMixinMod() throws IOException {
        Path widened = buildWidenedGameClasses();

        Map<String, byte[]> classes = SourceCompiler.compile(Map.of(
                "com.example.mixin.MinecraftMixin", """
                        package com.example.mixin;

                        import net.minecraft.client.Minecraft;
                        import org.spongepowered.asm.mixin.Mixin;
                        import org.spongepowered.asm.mixin.injection.At;
                        import org.spongepowered.asm.mixin.injection.Inject;
                        import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

                        @Mixin(Minecraft.class)
                        public class MinecraftMixin {
                            @Inject(method = "greeting", at = @At("HEAD"), cancellable = true)
                            private void octo$greeting(CallbackInfoReturnable<String> callback) {
                                callback.setReturnValue("modded");
                            }
                        }
                        """,
                "com.example.mixin.MinecraftTooltipMixin", """
                        package com.example.mixin;

                        import java.util.function.Supplier;

                        import net.minecraft.client.Minecraft;
                        import org.spongepowered.asm.mixin.Mixin;
                        import org.spongepowered.asm.mixin.injection.At;
                        import org.spongepowered.asm.mixin.injection.Inject;
                        import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

                        @Mixin(Minecraft.class)
                        public class MinecraftTooltipMixin {
                            @Inject(method = "tooltip", at = @At("HEAD"), cancellable = true)
                            private void octo$tooltip(CallbackInfoReturnable<String> callback) {
                                // An anonymous class declared inside a mixin. This
                                // method is about to be copied into Minecraft, so
                                // mixin renames the anonymous class into Minecraft's
                                // package and generates it only when asked for.
                                Supplier<String> tooltip = new Supplier<>() {
                                    @Override
                                    public String get() {
                                        return "modded tooltip";
                                    }
                                };

                                callback.setReturnValue(tooltip.get());
                            }
                        }
                        """,
                "com.example.mixin.MinecraftAccessor", """
                        package com.example.mixin;

                        import net.minecraft.client.Minecraft;
                        import org.spongepowered.asm.mixin.Mixin;
                        import org.spongepowered.asm.mixin.gen.Invoker;

                        @Mixin(Minecraft.class)
                        public interface MinecraftAccessor {
                            @Invoker("secret")
                            String octo$callSecret();
                        }
                        """,
                "com.example.mod.WidenedScreen", """
                        package com.example.mod;

                        // The game declares this class final; the mod's access widener
                        // is the only reason this compiles and links.
                        public class WidenedScreen extends net.minecraft.client.gui.Screen {
                        }
                        """,
                "com.example.mod.MixinMod", """
                        package com.example.mod;

                        import com.example.mixin.MinecraftAccessor;
                        import net.fabricmc.api.ModInitializer;
                        import net.minecraft.client.Minecraft;

                        public class MixinMod implements ModInitializer {
                            public static String greeting;
                            public static String tooltipViaInnerClass;
                            public static String secretViaInvoker;
                            public static String secretViaWidener;
                            public static Boolean extendedFinalClass;

                            @Override
                            public void onInitialize() {
                                Minecraft game = Minecraft.getInstance();
                                greeting = game.greeting();
                                tooltipViaInnerClass = game.tooltip();
                                secretViaInvoker = ((MinecraftAccessor) (Object) game).octo$callSecret();
                                secretViaWidener = game.secret();
                                extendedFinalClass = "screen".equals(new WidenedScreen().title());
                            }
                        }
                        """), List.of(widened.toString()));

        new JarBuilder()
                .classes(classes)
                .file("fabric.mod.json", """
                        {
                          "schemaVersion": 1,
                          "id": "mixinmod",
                          "version": "1.0.0",
                          "name": "Mixin Sample",
                          "environment": "*",
                          "entrypoints": { "main": ["com.example.mod.MixinMod"] },
                          "mixins": ["mixinmod.mixins.json"],
                          "accessWidener": "mixinmod.accesswidener"
                        }
                        """)
                .file("mixinmod.mixins.json", """
                        {
                          "required": true,
                          "minVersion": "0.8",
                          "package": "com.example.mixin",
                          "compatibilityLevel": "JAVA_17",
                          "mixins": ["MinecraftMixin", "MinecraftTooltipMixin", "MinecraftAccessor"],
                          "injectors": { "defaultRequire": 1 }
                        }
                        """)
                .file("mixinmod.accesswidener", """
                        accessWidener\tv2\tnamed
                        accessible\tmethod\tnet/minecraft/client/Minecraft\tsecret\t()Ljava/lang/String;
                        extendable\tclass\tnet/minecraft/client/gui/Screen
                        """)
                .writeTo(modsDir.resolve("mixin-sample.jar"));
    }
}
