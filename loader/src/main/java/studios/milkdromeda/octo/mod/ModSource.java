package studios.milkdromeda.octo.mod;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Read-only view of a mod's contents, whether it is a jar or an exploded directory. */
public interface ModSource extends Closeable {
    /** Where this came from, for logging. */
    Path path();

    Optional<byte[]> read(String entry);

    /** All entry names, using {@code /} separators. */
    List<String> entries();

    default boolean has(String entry) {
        return read(entry).isPresent();
    }

    static ModSource open(Path path) throws IOException {
        return Files.isDirectory(path) ? new Directory(path) : new Jar(path);
    }

    static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, in.available()));
        byte[] buffer = new byte[8192];
        int read;

        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }

        return out.toByteArray();
    }

    final class Jar implements ModSource {
        private final Path path;
        private final JarFile jar;

        Jar(Path path) throws IOException {
            this.path = path;
            this.jar = new JarFile(path.toFile());
        }

        @Override
        public Path path() {
            return path;
        }

        @Override
        public Optional<byte[]> read(String entry) {
            JarEntry found = jar.getJarEntry(entry);

            if (found == null || found.isDirectory()) {
                return Optional.empty();
            }

            try (InputStream in = jar.getInputStream(found)) {
                return Optional.of(readFully(in));
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public List<String> entries() {
            List<String> out = new ArrayList<>();
            Enumeration<JarEntry> enumeration = jar.entries();

            while (enumeration.hasMoreElements()) {
                JarEntry entry = enumeration.nextElement();

                if (!entry.isDirectory()) {
                    out.add(entry.getName());
                }
            }

            return out;
        }

        @Override
        public void close() throws IOException {
            jar.close();
        }
    }

    final class Directory implements ModSource {
        private final Path root;

        Directory(Path root) {
            this.root = root;
        }

        @Override
        public Path path() {
            return root;
        }

        @Override
        public Optional<byte[]> read(String entry) {
            Path file = root.resolve(entry);

            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }

            try {
                return Optional.of(Files.readAllBytes(file));
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        @Override
        public List<String> entries() {
            try (Stream<Path> walk = Files.walk(root)) {
                return walk.filter(Files::isRegularFile)
                        .map(file -> root.relativize(file).toString().replace('\\', '/'))
                        .toList();
            } catch (IOException e) {
                return List.of();
            }
        }

        @Override
        public void close() {
        }
    }
}
