package studios.milkdromeda.octo.installer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The installer, run against a directory laid out like a real {@code .minecraft}.
 *
 * <p>What matters here is that installing is additive and reversible: the
 * player's existing versions and profiles must come out the other side
 * untouched, and uninstalling must leave nothing behind.
 */
class InstallerTest {
    @TempDir
    Path gameDirectory;

    private final List<String> messages = new ArrayList<>();
    private final ClientInstaller.Log log = messages::add;

    private void givenMinecraftInstalled(String version) throws IOException {
        Path directory = gameDirectory.resolve("versions").resolve(version);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(version + ".json"), """
                { "id": "%s", "type": "release", "mainClass": "net.minecraft.client.main.Main" }
                """.formatted(version));
    }

    @Test
    @DisplayName("installing writes a profile that inherits from the vanilla version")
    void writesAnInheritingProfile() throws IOException {
        givenMinecraftInstalled("1.20.1");

        String versionId = new ClientInstaller(gameDirectory, "1.0.0", log).install("1.20.1");

        assertEquals("octo-loader-1.20.1", versionId);

        Path profile = gameDirectory.resolve("versions/octo-loader-1.20.1/octo-loader-1.20.1.json");
        assertTrue(Files.isRegularFile(profile));

        JsonObject json = JsonParser.parseString(Files.readString(profile, StandardCharsets.UTF_8)).getAsJsonObject();

        assertEquals("octo-loader-1.20.1", json.get("id").getAsString());
        assertEquals("1.20.1", json.get("inheritsFrom").getAsString(),
                "the vanilla version supplies the jar, assets and libraries");
        assertEquals("studios.milkdromeda.octo.launch.OctoMain", json.get("mainClass").getAsString());
        assertEquals("studios.milkdromeda:octo-loader:1.0.0",
                json.getAsJsonArray("libraries").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(List.of("--gameDir", "${game_directory}"),
                json.getAsJsonObject("arguments").getAsJsonArray("game").asList().stream()
                        .map(element -> element.getAsString()).toList(),
                "Octo must be told which mods directory belongs to this installation");

        assertTrue(Files.isRegularFile(MinecraftPaths.libraryPath(gameDirectory,
                        ClientInstaller.GROUP, ClientInstaller.ARTIFACT, "1.0.0")),
                "the loader should have been written into libraries/");
        assertTrue(Files.isDirectory(gameDirectory.resolve("mods")), "a mods folder should be ready to use");
    }

    @Test
    @DisplayName("the vanilla version is left exactly as it was")
    void doesNotTouchVanilla() throws IOException {
        givenMinecraftInstalled("1.20.1");
        Path vanilla = gameDirectory.resolve("versions/1.20.1/1.20.1.json");
        String before = Files.readString(vanilla, StandardCharsets.UTF_8);

        new ClientInstaller(gameDirectory, "1.0.0", log).install("1.20.1");

        assertEquals(before, Files.readString(vanilla, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a launcher profile is added without disturbing the ones already there")
    void addsALauncherProfile() throws IOException {
        givenMinecraftInstalled("1.20.1");
        Files.writeString(gameDirectory.resolve("launcher_profiles.json"), """
                { "profiles": { "existing": { "name": "My Profile", "lastVersionId": "1.20.1" } },
                  "settings": { "keepLauncherOpen": true } }
                """);

        new ClientInstaller(gameDirectory, "1.0.0", log).install("1.20.1");

        JsonObject root = JsonParser.parseString(
                Files.readString(gameDirectory.resolve("launcher_profiles.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject profiles = root.getAsJsonObject("profiles");

        assertTrue(profiles.has("existing"), "the player's own profile should survive");
        assertEquals("My Profile", profiles.getAsJsonObject("existing").get("name").getAsString());
        assertTrue(profiles.has("octo-loader-1.20.1"));
        assertEquals("octo-loader-1.20.1",
                profiles.getAsJsonObject("octo-loader-1.20.1").get("lastVersionId").getAsString());
        assertEquals(gameDirectory.toAbsolutePath().normalize().toString(),
                profiles.getAsJsonObject("octo-loader-1.20.1").get("gameDir").getAsString(),
                "the launcher profile should preserve the installation's game directory");
        assertTrue(root.has("settings"), "unrelated settings should be preserved");
    }

    @Test
    @DisplayName("installing for a version that is not there explains what to do")
    void refusesWhenVanillaIsMissing() {
        IOException error = assertThrows(IOException.class,
                () -> new ClientInstaller(gameDirectory, "1.0.0", log).install("1.20.1"));

        assertTrue(error.getMessage().contains("not installed"));
        assertTrue(error.getMessage().contains("launcher"), "the message should say how to fix it");
    }

    @Test
    @DisplayName("uninstalling removes everything the installer wrote")
    void uninstallLeavesNothingBehind() throws IOException {
        givenMinecraftInstalled("1.20.1");
        Files.writeString(gameDirectory.resolve("launcher_profiles.json"), """
                { "profiles": { "existing": { "name": "Keep me", "lastVersionId": "1.20.1" } } }
                """);
        ClientInstaller installer = new ClientInstaller(gameDirectory, "1.0.0", log);
        installer.install("1.20.1");

        installer.uninstall("1.20.1");

        assertFalse(Files.exists(gameDirectory.resolve("versions/octo-loader-1.20.1")));
        assertFalse(Files.exists(MinecraftPaths.libraryPath(gameDirectory,
                ClientInstaller.GROUP, ClientInstaller.ARTIFACT, "1.0.0")));
        assertTrue(Files.isRegularFile(gameDirectory.resolve("versions/1.20.1/1.20.1.json")),
                "the vanilla version should still be installed");

        JsonObject profiles = JsonParser.parseString(
                Files.readString(gameDirectory.resolve("launcher_profiles.json"), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("profiles");
        assertFalse(profiles.has("octo-loader-1.20.1"),
                "the launcher must not keep offering a profile whose version and library were removed");
        assertTrue(profiles.has("existing"), "uninstall must preserve unrelated launcher profiles");
    }

    @Test
    @DisplayName("a server install writes start scripts pointing at the loader")
    void installsIntoAServerDirectory() throws IOException {
        new ServerInstaller(gameDirectory, "1.0.0", log).install("server.jar");

        Path shell = gameDirectory.resolve("start-octo-server.sh");
        assertTrue(Files.isRegularFile(shell));

        String script = Files.readString(shell, StandardCharsets.UTF_8);
        assertTrue(script.contains("studios.milkdromeda.octo.launch.OctoMain"));
        assertTrue(script.contains("libraries/octo-loader-1.0.0.jar"));
        assertTrue(script.contains("--side server"));

        assertTrue(Files.isRegularFile(gameDirectory.resolve("start-octo-server.bat")));
        assertTrue(Files.isRegularFile(gameDirectory.resolve("libraries/octo-loader-1.0.0.jar")));
        assertTrue(Files.isDirectory(gameDirectory.resolve("mods")));
        assertTrue(messages.stream().anyMatch(message -> message.contains("server.jar is not here yet")),
                "the operator should be told the vanilla jar is still needed: " + messages);
    }

    @Test
    @DisplayName("the loader is carried inside the installer, so no download is needed")
    void loaderIsEmbedded() throws IOException {
        assertTrue(EmbeddedLoader.isAvailable());

        Path extracted = gameDirectory.resolve("out/octo-loader.jar");
        EmbeddedLoader.extractTo(extracted);

        assertTrue(Files.size(extracted) > 100_000, "the embedded loader should be a real jar");

        try (var jar = new java.util.jar.JarFile(extracted.toFile())) {
            assertTrue(jar.getJarEntry("studios/milkdromeda/octo/launch/OctoMain.class") != null,
                    "the embedded jar should contain the loader's entrypoint");
            assertTrue(jar.getJarEntry("net/fabricmc/api/ModInitializer.class") != null,
                    "and the upstream APIs mods link against");
        }
    }

    @Test
    @DisplayName("the command line reports the versions it can install into")
    void listsInstallableVersions() throws IOException {
        givenMinecraftInstalled("1.20.1");
        givenMinecraftInstalled("1.21.4");
        new ClientInstaller(gameDirectory, "1.0.0", log).install("1.20.1");

        givenInheritedLoaderProfile("fabric-loader-0.16.10-1.21.4", "1.21.4",
                "net.fabricmc.loader.impl.launch.knot.KnotClient");
        givenInheritedLoaderProfile("neoforge-21.4.124", "1.21.4", "cpw.mods.bootstraplauncher.BootstrapLauncher");

        List<String> installable = ClientInstaller.installableVersions(gameDirectory);

        assertEquals(List.of("1.21.4", "1.20.1"), installable,
                "only Minecraft itself, never a loader profile, should be offered as a base");
    }

    private void givenInheritedLoaderProfile(String id, String minecraftVersion, String mainClass) throws IOException {
        Path directory = gameDirectory.resolve("versions").resolve(id);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(id + ".json"), """
                { "id": "%s", "inheritsFrom": "%s", "type": "release", "mainClass": "%s" }
                """.formatted(id, minecraftVersion, mainClass));
    }

    @Test
    @DisplayName("version names are ordered numerically instead of lexicographically")
    void sortsVersionsNaturally() throws IOException {
        givenMinecraftInstalled("1.9.4");
        givenMinecraftInstalled("1.20.1");
        givenMinecraftInstalled("1.21.4");

        assertEquals(List.of("1.21.4", "1.20.1", "1.9.4"),
                ClientInstaller.installableVersions(gameDirectory));
    }

    @Test
    @DisplayName("the command line runs headless and reports success")
    void cliInstalls() throws IOException {
        givenMinecraftInstalled("1.20.1");

        int status = Installer.runCli(new ArrayList<>(List.of(
                "client", "--dir", gameDirectory.toString(), "--mcVersion", "1.20.1")));

        assertEquals(0, status);
        assertTrue(Files.isRegularFile(gameDirectory.resolve("versions/octo-loader-1.20.1/octo-loader-1.20.1.json")));
    }
}
