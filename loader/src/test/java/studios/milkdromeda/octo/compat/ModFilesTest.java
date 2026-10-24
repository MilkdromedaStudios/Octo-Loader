package studios.milkdromeda.octo.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import studios.milkdromeda.octo.compat.fabric.FabricModContainer;
import studios.milkdromeda.octo.launch.LaunchContext;
import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.runtime.BuiltinMods;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.testkit.JarBuilder;

/**
 * A mod's files, and the game's, as the loader API hands them out.
 *
 * <p>The dedicated server died loading its first translated string because
 * Fabric API asked for the {@code minecraft} container's language file and got
 * nothing — there was no such container, and a mod's root path pointed at its
 * jar on disk instead of into it.
 */
class ModFilesTest {
    @TempDir
    Path temporary;

    @AfterEach
    void reset() {
        OctoRuntime.reset();
    }

    @Test
    @DisplayName("a mod's root path is inside its jar, so its own files resolve")
    void rootPathIsInsideTheJar() throws Exception {
        Path jar = new JarBuilder().file("assets/example/lang/en_us.json", "{}")
                .writeTo(temporary.resolve("example.jar"));
        LoadedMod mod = new LoadedMod(new ModCandidate(jar,
                ModMetadata.builder("example", ModFormat.FABRIC).version("1.0").build(), null));

        ModContainer container = FabricModContainer.of(mod);
        Path root = container.getRootPaths().get(0);

        assertTrue(Files.exists(root.resolve("assets/example/lang/en_us.json")));
        assertTrue(container.findPath("assets/example/lang/en_us.json").isPresent());
    }

    @Test
    @DisplayName("the game, the JVM and the loader are mods, and the game's files can be read through it")
    void builtinsExist() throws Exception {
        Path gameJar = new JarBuilder().file("assets/minecraft/lang/en_us.json", "{\"a\":\"b\"}")
                .writeTo(temporary.resolve("client.jar"));
        LaunchContext context = LaunchContext.builder(temporary).minecraftVersion("26.3").gameJar(gameJar).build();
        OctoRuntime runtime = OctoRuntime.initialise(context);
        BuiltinMods.create(context).forEach(runtime::register);

        ModContainer minecraft = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow();

        assertEquals("26.3", minecraft.getMetadata().getVersion().getFriendlyString());
        assertTrue(minecraft.findPath("assets/minecraft/lang/en_us.json").isPresent());
        assertTrue(FabricLoader.getInstance().isModLoaded("java"));
        assertTrue(FabricLoader.getInstance().isModLoaded("fabricloader"));
        assertTrue(FabricLoader.getInstance().isModLoaded("mixinextras"));
    }

    @Test
    @DisplayName("a mixin config marked for the client is not registered on a dedicated server")
    void clientMixinConfigsStayOnTheClient() {
        ModMetadata metadata = ModMetadata.builder("example", ModFormat.FABRIC)
                .mixinConfig("example.mixins.json", Side.BOTH)
                .mixinConfig("example.client.mixins.json", Side.CLIENT)
                .build();

        assertEquals(List.of("example.mixins.json"), metadata.mixinConfigs(Side.SERVER));
        assertEquals(List.of("example.mixins.json", "example.client.mixins.json"), metadata.mixinConfigs(Side.CLIENT));
        assertFalse(metadata.mixinConfigs(Side.SERVER).contains("example.client.mixins.json"));
    }
}
