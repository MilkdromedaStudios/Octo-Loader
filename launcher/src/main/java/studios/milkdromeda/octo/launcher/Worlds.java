package studios.milkdromeda.octo.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import studios.milkdromeda.octo.common.WorldBackups;
import studios.milkdromeda.octo.common.WorldContent;

/** The worlds in a Minecraft folder, and what Octo knows about each. */
public final class Worlds {
    /** One world. */
    public record World(Path folder, String name, Instant lastPlayed, int backups, Set<String> missingMods,
            Set<String> mods) {
        public boolean needsAttention() {
            return !missingMods.isEmpty();
        }
    }

    private Worlds() {
    }

    /** Every world in {@code saves}, most recently played first. */
    public static List<World> list(Path gameDirectory) {
        List<World> out = new ArrayList<>();
        Path saves = gameDirectory.resolve("saves");

        if (!Files.isDirectory(saves)) {
            return out;
        }

        try (var folders = Files.list(saves)) {
            folders.filter(folder -> Files.isRegularFile(folder.resolve("level.dat"))).forEach(folder -> {
                out.add(describe(folder));
            });
        } catch (IOException e) {
            return out;
        }

        out.sort(Comparator.comparing(World::lastPlayed).reversed());
        return out;
    }

    public static World describe(Path folder) {
        Instant played;

        try {
            FileTime time = Files.getLastModifiedTime(folder.resolve("level.dat"));
            played = time.toInstant();
        } catch (IOException e) {
            played = Instant.EPOCH;
        }

        WorldContent content = WorldContent.read(folder);
        Set<String> mods = content == null ? Set.of() : new TreeSet<>(content.mods().keySet());
        return new World(folder, folder.getFileName().toString(), played,
                WorldBackups.list(WorldBackups.folderFor(folder)).size(), missingMods(folder), mods);
    }

    /** The mods the world guard last found missing, from its report; none when it has none. */
    static Set<String> missingMods(Path folder) {
        Path report = folder.resolve("octo/guard-report.txt");
        Set<String> out = new TreeSet<>();

        if (!Files.isRegularFile(report)) {
            return out;
        }

        try {
            for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
                int at = line.indexOf("does not have now: [");

                if (at >= 0) {
                    String list = line.substring(at + "does not have now: [".length(), line.lastIndexOf(']'));

                    for (String mod : list.split(",")) {
                        if (!mod.isBlank()) {
                            out.add(mod.strip());
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // Nothing known, then.
        }

        out.removeAll(WorldContent.accepted(folder));
        return out;
    }

    /** The world played most recently, if there is one. */
    public static World lastPlayed(Path gameDirectory) {
        List<World> all = list(gameDirectory);
        return all.isEmpty() ? null : all.get(0);
    }
}
