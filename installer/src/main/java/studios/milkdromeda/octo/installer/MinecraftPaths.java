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

    /** Every installed version id that has a profile json, newest version first. */
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

        out.sort(MinecraftPaths::compareVersionsNewestFirst);
        return out;
    }

    /**
     * Compares the numeric parts of version names as numbers. Plain string
     * ordering puts 1.9 ahead of 1.20, which made the installer's list appear
     * scrambled and caused the CLI default to choose an old game.
     */
    static int compareVersionsNewestFirst(String left, String right) {
        String[] leftParts = left.split("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)");
        String[] rightParts = right.split("(?<=\\d)(?=\\D)|(?<=\\D)(?=\\d)");
        int length = Math.max(leftParts.length, rightParts.length);

        for (int i = 0; i < length; i++) {
            if (i >= leftParts.length) {
                return 1;
            }
            if (i >= rightParts.length) {
                return -1;
            }

            String a = leftParts[i];
            String b = rightParts[i];
            int comparison;

            if (a.chars().allMatch(Character::isDigit) && b.chars().allMatch(Character::isDigit)) {
                comparison = compareNumericParts(a, b);
            } else {
                comparison = a.compareToIgnoreCase(b);
            }

            if (comparison != 0) {
                return -comparison;
            }
        }

        return right.compareTo(left);
    }

    private static int compareNumericParts(String left, String right) {
        String a = left.replaceFirst("^0+(?!$)", "");
        String b = right.replaceFirst("^0+(?!$)", "");
        int length = Integer.compare(a.length(), b.length());
        return length != 0 ? length : a.compareTo(b);
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
