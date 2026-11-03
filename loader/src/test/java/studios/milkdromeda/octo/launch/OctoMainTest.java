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

    private Path clientJar(Path home, String builtFor) throws IOException {
        Path folder = home.resolve(".octo-launcher").resolve("client");
        Files.createDirectories(folder);
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Octo-Client-Minecraft", builtFor);

        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(
                Files.newOutputStream(folder.resolve("octo-client.jar")), manifest)) {
            out.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json"));
            out.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }

        return folder.toAbsolutePath().normalize();
    }

    @Test
    @DisplayName("Octo Client loads from Octo Launcher's folder for the Minecraft it is built for")
    void findsOctoClientForItsVersion() throws IOException {
        Path home = tempDir.resolve("home");
        Path folder = clientJar(home, "26.3");

        assertEquals(folder, OctoMain.octoClientFolder(home.toString(), "26.3"));
        assertEquals(folder, OctoMain.clientIn(home.resolve(".octo-launcher"), "26.3"),
                "where Octo Launcher says it keeps its files");
        assertEquals(null, OctoMain.clientIn(tempDir.resolve("elsewhere"), "26.3"));
        assertEquals(folder, OctoMain.octoClientFolder(home.toString(), "26.3.1"), "a patch release too");
        assertEquals(null, OctoMain.octoClientFolder(home.toString(), "1.21.1"), "not another version");
        assertEquals(null, OctoMain.octoClientFolder(home.toString(), "26.30"), "nor one that only starts alike");
        assertEquals(null, OctoMain.octoClientFolder(tempDir.resolve("nobody").toString(), "26.3"),
                "nor when the launcher has not put it there (or the player turned it off)");
    }
}
