package studios.milkdromeda.octo.launcher.game;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What is in a Minecraft folder, by kind, and deleting a kind of it. Only the
 * folders named here are ever removed, never the Minecraft folder itself, and
 * links are removed as links, never followed out of it.
 */
public final class GameFolder {
    /** A kind of thing in the folder, and the folders it is kept in. */
    public enum Kind {
        GAME_FILES("Downloaded game files", "Minecraft, its libraries, sounds and textures, and Java. Downloaded "
                + "again the next time you play.", false, "versions", "libraries", "assets", ".octo"),
        MODS("Mods and their settings", "Your mods folder and every mod's config.", false, "mods", "config"),
        PACKS("Packs, screenshots and logs", "Resource packs, shader packs, screenshots, logs and crash reports.",
                false, "resourcepacks", "shaderpacks", "screenshots", "logs", "crash-reports"),
        WORLDS("Worlds", "Every world in your saves folder. Gone for good, unless you keep their backups.", true,
                "saves"),
        BACKUPS("World backups", "The backups Octo has made of your worlds.", true, "backups");

        public final String title;
        public final String detail;
        /** Something that cannot be downloaded again: asked about twice. */
        public final boolean irreplaceable;
        final List<String> folders;

        Kind(String title, String detail, boolean irreplaceable, String... folders) {
            this.title = title;
            this.detail = detail;
            this.irreplaceable = irreplaceable;
            this.folders = List.of(folders);
        }
    }

    private GameFolder() {
    }

    /** The folders of a kind in this Minecraft folder; the runtimes folder holds Octo's downloaded Java. */
    public static List<Path> folders(Path gameDirectory, Kind kind, Path runtimes) {
        List<Path> out = new ArrayList<>();

        for (String name : kind.folders) {
            out.add(gameDirectory.resolve(name));
        }

        if (kind == Kind.GAME_FILES && runtimes != null) {
            out.add(runtimes);
        }

        return out;
    }

    /** How many bytes each kind takes. */
    public static Map<Kind, Long> sizes(Path gameDirectory, Path runtimes) {
        Map<Kind, Long> out = new EnumMap<>(Kind.class);

        for (Kind kind : Kind.values()) {
            long total = 0;

            for (Path folder : folders(gameDirectory, kind, runtimes)) {
                total += size(folder);
            }

            out.put(kind, total);
        }

        return out;
    }

    static long size(Path folder) {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }

        long[] total = { 0 };

        try {
            Files.walkFileTree(folder, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    total[0] += attributes.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // What was counted.
        }

        return total[0];
    }

    /**
     * Whether this looks like a Minecraft folder: deleting is refused in any
     * other, so a mistyped folder setting cannot empty a home folder.
     */
    public static boolean looksLikeMinecraft(Path gameDirectory) {
        Path normalized = gameDirectory.toAbsolutePath().normalize();
        String home = System.getProperty("user.home");

        if (normalized.getParent() == null || home != null && normalized.equals(Path.of(home).toAbsolutePath()
                .normalize())) {
            return false;
        }

        return Files.isRegularFile(normalized.resolve("launcher_profiles.json"))
                || Files.isRegularFile(normalized.resolve("options.txt"))
                || Files.isDirectory(normalized.resolve("versions"))
                || Files.isDirectory(normalized.resolve("saves"));
    }

    /**
     * Deletes the kinds chosen.
     *
     * @return the bytes freed
     */
    public static long delete(Path gameDirectory, Set<Kind> kinds, Path runtimes) throws IOException {
        if (!looksLikeMinecraft(gameDirectory)) {
            throw new IOException(gameDirectory + " does not look like a Minecraft folder, so nothing was deleted");
        }

        long freed = 0;

        for (Kind kind : kinds) {
            for (Path folder : folders(gameDirectory, kind, runtimes)) {
                freed += size(folder);
                deleteTree(folder);
            }
        }

        return freed;
    }

    /** Removes a folder and everything in it; a link in it is removed, not what it points to. */
    static void deleteTree(Path folder) throws IOException {
        if (Files.isSymbolicLink(folder)) {
            Files.delete(folder);
            return;
        }

        if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }

                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
