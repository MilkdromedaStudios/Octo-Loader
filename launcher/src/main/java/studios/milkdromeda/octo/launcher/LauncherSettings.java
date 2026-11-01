package studios.milkdromeda.octo.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import studios.milkdromeda.octo.installer.MinecraftPaths;

/**
 * What the player set, kept in {@code <home>/.octo-launcher/settings.json}:
 * outside every Minecraft folder, so it survives one being moved or wiped.
 */
public final class LauncherSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** The Minecraft folder: saves, versions, libraries, launcher_profiles.json. */
    public String gameDirectory = MinecraftPaths.defaultGameDirectory().toString();
    /** The official launcher's program, if it is not where it is usually installed. */
    public String officialLauncher = "";
    /** The Minecraft version Play installs Octo for and opens. */
    public String selectedVersion = "";
    /** Look for a newer Octo Launcher and Octo Loader each time the app opens. */
    public boolean checkForUpdates = true;
    /** Install a newer loader into every Octo installation as soon as one is downloaded. */
    public boolean updateLoaderAutomatically = true;
    /** Back up the most recently played world each time Play is pressed. */
    public boolean backupBeforeLaunch = true;
    /** How many automatic backups of each world to keep; manual ones are never removed. */
    public int backupsToKeep = 10;
    /** List snapshots and pre-releases with the releases. */
    public boolean showSnapshots = false;
    /** Start the game with Octo Client, for the Minecraft versions it is built for. */
    public boolean octoClient = true;
    /** Play through the official Minecraft Launcher instead of starting the game from Octo. */
    public boolean useOfficialLauncher = false;
    /** The most memory the game may use, in megabytes. */
    public int memoryMb = 4096;
    /** A Java of the player's own to run the game with; empty means the one Mojang makes for each version. */
    public String javaPath = "";
    /** More options for Java, as typed. */
    public String jvmArguments = "";
    /** The Microsoft app registration to sign in with, when this build has none of its own. */
    public String microsoftClientId = "";

    public Path gameDir() {
        return Path.of(gameDirectory);
    }

    /** Where Octo keeps the Java it downloads for Minecraft. */
    public static Path runtimes() {
        return home().resolve("runtime");
    }

    public static Path home() {
        return Path.of(System.getProperty("user.home"), ".octo-launcher");
    }

    private static Path file() {
        return home().resolve("settings.json");
    }

    public static LauncherSettings load() {
        try {
            if (Files.isRegularFile(file())) {
                LauncherSettings read = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8),
                        LauncherSettings.class);

                if (read != null) {
                    return read;
                }
            }
        } catch (IOException | JsonParseException e) {
            // Defaults, then.
        }

        return new LauncherSettings();
    }

    public void save() throws IOException {
        Files.createDirectories(home());
        Path part = file().resolveSibling("settings.json.part");
        Files.writeString(part, GSON.toJson(this) + "\n", StandardCharsets.UTF_8);
        Files.move(part, file(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
