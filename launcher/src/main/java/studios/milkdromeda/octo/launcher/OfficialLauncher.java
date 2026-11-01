package studios.milkdromeda.octo.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The official Minecraft Launcher, for players who would rather play through
 * it (or a build of Octo with no Microsoft sign-in of its own): it signs them
 * in, downloads the game and starts it.
 *
 * <p>Octo Launcher prepares everything around that — the Octo installation,
 * the loader, the world backups — selects the installation, and hands over.
 */
public final class OfficialLauncher {
    /** The Microsoft Store and Xbox app's Minecraft Launcher, by its application id. */
    private static final String STORE_APP = "shell:AppsFolder\\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft";

    private OfficialLauncher() {
    }

    /** Where the launcher is usually installed on this system, the first that exists. */
    public static Path find(String configured) {
        if (configured != null && !configured.isBlank() && Files.exists(Path.of(configured))) {
            return Path.of(configured);
        }

        for (Path candidate : candidates()) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    static List<Path> candidates() {
        List<Path> out = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            for (String root : new String[] { System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"),
                    "C:\\Program Files (x86)", "C:\\Program Files" }) {
                if (root != null) {
                    out.add(Path.of(root, "Minecraft Launcher", "MinecraftLauncher.exe"));
                    out.add(Path.of(root, "Minecraft", "MinecraftLauncher.exe"));
                }
            }
        } else if (os.contains("mac")) {
            out.add(Path.of("/Applications/Minecraft.app"));
            out.add(Path.of(System.getProperty("user.home"), "Applications", "Minecraft.app"));
        } else {
            out.add(Path.of("/usr/bin/minecraft-launcher"));
            out.add(Path.of("/opt/minecraft-launcher/minecraft-launcher"));
            out.add(Path.of(System.getProperty("user.home"), ".local/bin/minecraft-launcher"));
            out.add(Path.of("/var/lib/flatpak/exports/bin/com.mojang.Minecraft"));
            out.add(Path.of(System.getProperty("user.home"), ".local/share/flatpak/exports/bin/com.mojang.Minecraft"));
        }

        return out;
    }

    /**
     * Opens the official launcher on the Minecraft folder, with the profile
     * selected. Signing in, downloading and pressing Play happen there.
     */
    public static void open(Path program, Path gameDirectory) throws IOException {
        List<String> command = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean customFolder = !gameDirectory.toAbsolutePath().normalize()
                .equals(studios.milkdromeda.octo.installer.MinecraftPaths.defaultGameDirectory().toAbsolutePath()
                        .normalize());

        if (program == null) {
            if (!os.contains("win")) {
                throw new IOException("the Minecraft Launcher was not found; set where it is in Settings");
            }

            // The Microsoft Store / Xbox app install, which has no path of its own.
            command.add("explorer.exe");
            command.add(STORE_APP);
        } else if (os.contains("mac") && program.toString().endsWith(".app")) {
            command.add("open");
            command.add("-a");
            command.add(program.toString());

            if (customFolder) {
                command.add("--args");
                command.add("--workDir");
                command.add(gameDirectory.toString());
            }
        } else {
            command.add(program.toString());

            if (customFolder) {
                command.add("--workDir");
                command.add(gameDirectory.toString());
            }
        }

        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    /**
     * Makes a profile the one the launcher has selected when it opens: it
     * selects the profile used most recently.
     */
    public static void select(Path gameDirectory, String profileKey) throws IOException {
        Path file = gameDirectory.resolve("launcher_profiles.json");

        if (!Files.isRegularFile(file)) {
            return;
        }

        JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));

        if (!(parsed instanceof JsonObject root) || !(root.get("profiles") instanceof JsonObject profiles)
                || !(profiles.get(profileKey) instanceof JsonObject profile)) {
            return;
        }

        profile.addProperty("lastUsed", Instant.now().toString());
        Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n",
                StandardCharsets.UTF_8);
    }

    /** Whether the official launcher has been run in this folder: it makes this file. */
    public static boolean hasRunIn(Path gameDirectory) {
        return Files.isRegularFile(gameDirectory.resolve("launcher_profiles.json"));
    }
}
