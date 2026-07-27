package studios.milkdromeda.octo.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OctoMainTest {
    @TempDir
    Path tempDir;

    @Test
    @DisplayName("an explicit game directory always wins")
    void usesExplicitGameDirectory() {
        Path declared = tempDir.resolve("instance");
        String unrelatedClassPath = tempDir.resolve("other/versions/1.21.8/1.21.8.jar").toString();

        assertEquals(declared.toAbsolutePath().normalize(),
                OctoMain.gameDirectory(List.of("--gameDir", declared.toString()), unrelatedClassPath));
    }

    @Test
    @DisplayName("the Minecraft directory is recovered from the version jar for old profiles")
    void infersGameDirectoryFromVersionJar() {
        Path gameDir = tempDir.resolve("minecraft");
        String classPath = String.join(File.pathSeparator,
                tempDir.resolve("launcher/runtime.jar").toString(),
                gameDir.resolve("libraries/studios/milkdromeda/octo-loader/1.0/octo-loader-1.0.jar").toString(),
                gameDir.resolve("versions/1.21.8/1.21.8.jar").toString());

        assertEquals(gameDir.toAbsolutePath().normalize(), OctoMain.gameDirectory(List.of(), classPath));
    }

    @Test
    @DisplayName("an unexpanded launcher placeholder is recovered from the libraries path")
    void ignoresUnexpandedPlaceholder() {
        Path gameDir = tempDir.resolve("custom-instance");
        String classPath = gameDir.resolve("libraries/studios/milkdromeda/octo-loader.jar").toString();

        assertEquals(gameDir.toAbsolutePath().normalize(),
                OctoMain.gameDirectory(List.of("--gameDir", "${game_directory}"), classPath));
    }

    @Test
    @DisplayName("Windows falls back to the standard AppData Minecraft directory")
    void usesAppDataWhenLauncherProvidesNoLocation() {
        Path appData = tempDir.resolve("AppData/Roaming");

        assertEquals(appData.resolve(".minecraft").toAbsolutePath().normalize(),
                OctoMain.gameDirectory(List.of(), "", appData.toString(), tempDir.toString()));
    }

    @Test
    @DisplayName("other platforms fall back to the standard home Minecraft directory")
    void usesHomeWhenLauncherProvidesNoLocation() {
        Path home = tempDir.resolve("home");

        assertEquals(home.resolve(".minecraft").toAbsolutePath().normalize(),
                OctoMain.gameDirectory(List.of(), "", null, home.toString()));
    }

    @Test
    @DisplayName("an empty launcher profile falls back to the installation mods folder")
    void findsModsInSharedInstallation() throws IOException {
        Path instance = tempDir.resolve("profiles/Octo");
        Path installation = tempDir.resolve("minecraft");
        Path sharedMods = installation.resolve("mods");
        Files.createDirectories(sharedMods);
        Files.writeString(sharedMods.resolve("example.jar"), "mod");

        String classPath = installation.resolve("versions/1.21.8/1.21.8.jar").toString();

        assertEquals(List.of(sharedMods.toAbsolutePath().normalize()),
                OctoMain.defaultModDirectories(instance, classPath));
    }

    @Test
    @DisplayName("instance mods remain isolated when both folders contain mods")
    void prefersInstanceMods() throws IOException {
        Path instance = tempDir.resolve("profiles/Octo");
        Path instanceMods = instance.resolve("mods");
        Path installation = tempDir.resolve("minecraft");
        Path sharedMods = installation.resolve("mods");
        Files.createDirectories(instanceMods);
        Files.createDirectories(sharedMods);
        Files.writeString(instanceMods.resolve("instance.jar"), "mod");
        Files.writeString(sharedMods.resolve("shared.jar"), "mod");

        String classPath = installation.resolve("libraries/octo-loader.jar").toString();

        assertEquals(List.of(instanceMods.toAbsolutePath().normalize()),
                OctoMain.defaultModDirectories(instance, classPath));
    }
}
