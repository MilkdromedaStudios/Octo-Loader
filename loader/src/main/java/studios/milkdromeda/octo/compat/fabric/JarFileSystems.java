package studios.milkdromeda.octo.compat.fabric;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
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
final class JarFileSystems {
    private static final Map<Path, FileSystem> OPEN = new ConcurrentHashMap<>();

    private JarFileSystems() {
    }

    static FileSystem get(Path jar) throws IOException {
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
