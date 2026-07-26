package studios.milkdromeda.octo.installer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Finds where Minecraft keeps its files on each platform. */
public final class MinecraftPaths {
    private MinecraftPaths() {
    }

    public static Path defaultGameDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            return Path.of(appData != null ? appData : home, ".minecraft");
        }

        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "minecraft");
        }

        return Path.of(home, ".minecraft");
    }

    /** Every installed version id that has a profile json, newest name first. */
    public static List<String> installedVersions(Path gameDirectory) {
        Path versions = gameDirectory.resolve("versions");
        List<String> out = new ArrayList<>();

        if (!Files.isDirectory(versions)) {
            return out;
        }

        try (var stream = Files.list(versions)) {
            for (Path directory : stream.filter(Files::isDirectory).toList()) {
                String id = directory.getFileName().toString();

                if (Files.isRegularFile(directory.resolve(id + ".json"))) {
                    out.add(id);
                }
            }
        } catch (java.io.IOException e) {
            return out;
        }

        out.sort((left, right) -> right.compareTo(left));
        return out;
    }

    /** Where a Maven coordinate lands under {@code libraries/}. */
    public static Path libraryPath(Path gameDirectory, String group, String artifact, String version) {
        Path path = gameDirectory.resolve("libraries");

        for (String segment : group.split("\\.")) {
            path = path.resolve(segment);
        }

        return path.resolve(artifact).resolve(version).resolve(artifact + "-" + version + ".jar");
    }
}
