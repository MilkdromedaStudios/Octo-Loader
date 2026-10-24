package studios.milkdromeda.octo.compat.fabric;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps one zip file system per mod jar.
 *
 * <p>Mods ask for paths inside their own jar constantly (configs, data files,
 * resource lookups). Opening a file system per call is expensive, and closing
 * one out from under a mod that kept the {@link Path} breaks it, so they are
 * opened once and kept for the life of the process.
 */
public final class JarFileSystems {
    private static final Map<Path, FileSystem> OPEN = new ConcurrentHashMap<>();

    private JarFileSystems() {
    }

    /**
     * The root of a mod's files: the jar's own file system, or the directory
     * itself for a mod that is not packed.
     *
     * <p>This is what Fabric means by a root path and what Quilt means by
     * {@code rootPath()}. Handing back the jar's location on disk instead looks
     * almost the same and resolves nothing: {@code mods/x.jar/assets/x/lang}
     * does not exist, so every mod reading its own assets or data through the
     * loader found none of them.
     */
    public static Path root(Path jar) {
        if (Files.isDirectory(jar)) {
            return jar;
        }

        try {
            return get(jar).getPath("/");
        } catch (IOException | RuntimeException e) {
            return jar;
        }
    }

    public static FileSystem get(Path jar) throws IOException {
        Path key = jar.toAbsolutePath().normalize();
        FileSystem existing = OPEN.get(key);

        if (existing != null && existing.isOpen()) {
            return existing;
        }

        synchronized (OPEN) {
            FileSystem current = OPEN.get(key);

            if (current != null && current.isOpen()) {
                return current;
            }

            URI uri = URI.create("jar:" + key.toUri());
            FileSystem created;

            try {
                created = FileSystems.newFileSystem(uri, Map.of());
            } catch (FileSystemAlreadyExistsException e) {
                created = FileSystems.getFileSystem(uri);
            }

            OPEN.put(key, created);
            return created;
        }
    }
}
