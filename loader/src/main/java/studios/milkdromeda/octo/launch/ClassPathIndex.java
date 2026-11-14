package studios.milkdromeda.octo.launch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * Which class path entry has which file, known in advance.
 *
 * <p>A class loader over a list of jars answers "who has this name" by asking
 * each jar in turn, and the usual answer to the question as the loader's
 * transformers ask it is "nobody": does the class a mod names still exist,
 * does this one, does that one. On a modded game that is eighty jars asked, tens
 * of thousands of times, to be told no. Here every jar's table of contents is
 * read once, as the jar goes on the path, and after that a name is one lookup
 * whether it is there or not.
 *
 * <p>The order of the class path is kept: a name two jars have belongs to the
 * one added first, as it does to a {@link java.net.URLClassLoader}. A
 * multi-release jar is read as this Java reads it. A directory is not indexed
 * at all, since its files can change under a running game in a development
 * environment; it is asked each time, in its turn.
 */
final class ClassPathIndex {
    private static final OctoLog LOG = OctoLog.of(ClassPathIndex.class);

    /** One entry of the class path. */
    static final class Source {
        private final int ordinal;
        private final Path path;
        private final URL url;
        /** {@code jar:file:/mods/x.jar!/}, which every resource URL of a jar starts with. */
        private final String urlPrefix;
        private final JarFile jar;

        private Source(int ordinal, Path path, URL url, JarFile jar) {
            this.ordinal = ordinal;
            this.path = path;
            this.url = url;
            this.jar = jar;
            this.urlPrefix = jar == null ? url.toString() : "jar:" + url + "!/";
        }

        Path path() {
            return path;
        }

        /** The entry itself, as a code source names it: {@code file:/mods/x.jar}. */
        URL url() {
            return url;
        }

        boolean isDirectory() {
            return jar == null;
        }

        boolean has(String name) {
            if (jar == null) {
                return isSafe(name) && Files.exists(path.resolve(name));
            }

            return jar.getJarEntry(name) != null;
        }

        /** The file's bytes, or {@code null} when this entry does not have it. */
        byte[] read(String name) throws IOException {
            if (jar == null) {
                Path file = path.resolve(name);
                return isSafe(name) && Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
            }

            JarEntry entry = jar.getJarEntry(name);

            if (entry == null || entry.isDirectory()) {
                return null;
            }

            try (InputStream in = jar.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }

        URL resource(String name) {
            try {
                if (jar == null) {
                    return path.resolve(name).toUri().toURL();
                }

                return URI.create(urlPrefix + encode(name)).toURL();
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        /** A directory is never asked for a name that would climb out of it. */
        private static boolean isSafe(String name) {
            return !name.isEmpty() && !name.startsWith("/") && !name.contains("..") && name.indexOf('\0') < 0;
        }

        @Override
        public String toString() {
            return path.toString();
        }
    }

    /** Every entry, in class path order. */
    private final List<Source> sources = new CopyOnWriteArrayList<>();
    /** The directories among them, which are asked rather than indexed. */
    private final List<Source> directories = new CopyOnWriteArrayList<>();
    /** A name to the entry that has it: a {@link Source}, or a {@code Source[]} in order when several do. */
    private final ConcurrentHashMap<String, Object> owners = new ConcurrentHashMap<>(1 << 16);
    private final ConcurrentHashMap<String, Source> byPath = new ConcurrentHashMap<>();
    /** Set when an entry could not be listed, and the index can no longer say "nobody has it". */
    private volatile boolean incomplete;

    /**
     * Puts an entry on the path.
     *
     * @return the entry, or {@code null} when it is already there
     */
    synchronized Source add(Path entry, URL url) {
        Path path = entry.toAbsolutePath().normalize();
        String key = path.toString();

        if (byPath.containsKey(key)) {
            return null;
        }

        Source source;

        if (Files.isDirectory(path)) {
            source = new Source(sources.size(), path, url, null);
            directories.add(source);
        } else {
            JarFile jar;

            try {
                // Read as the class loader beside this one reads it: unverified,
                // and with the classes this Java's version selects.
                jar = new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version());
            } catch (IOException | RuntimeException e) {
                LOG.warn("could not list {}, so every lookup will ask it: {}", path.getFileName(), e.toString());
                incomplete = true;
                return null;
            }

            source = new Source(sources.size(), path, url, jar);
            list(source, jar);

            if (namesMoreJars(path, jar)) {
                // Its manifest puts further jars on the path, which the class
                // loader follows by itself and this index does not.
                incomplete = true;
            }
        }

        sources.add(source);
        byPath.put(key, source);
        return source;
    }

    /** Whether a jar's manifest has a {@code Class-Path} naming a file that is really there. */
    private static boolean namesMoreJars(Path path, JarFile jar) {
        try {
            java.util.jar.Manifest manifest = jar.getManifest();
            String more = manifest == null ? null
                    : manifest.getMainAttributes().getValue(java.util.jar.Attributes.Name.CLASS_PATH);

            if (more == null || more.isBlank()) {
                return false;
            }

            for (String entry : more.trim().split("\\s+")) {
                try {
                    Path named = Path.of(path.getParent().toUri().resolve(entry));

                    if (Files.exists(named)) {
                        return true;
                    }
                } catch (RuntimeException e) {
                    // Not a path this file system has: nothing to follow.
                }
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }

        return false;
    }

    private void list(Source source, JarFile jar) {
        // A multi-release jar answers for its base names and for the versioned
        // paths themselves; both are what a lookup by name can find in it.
        if (jar.isMultiRelease()) {
            jar.versionedStream().forEach(entry -> own(entry.getName(), source));
        }

        jar.stream().forEach(entry -> own(entry.getName(), source));
    }

    private void own(String name, Source source) {
        String key = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;

        owners.merge(key, source, (existing, added) -> {
            if (existing == added) {
                return existing;
            }

            if (existing instanceof Source[] several) {
                if (several[several.length - 1] == added) {
                    return existing;
                }

                Source[] grown = java.util.Arrays.copyOf(several, several.length + 1);
                grown[several.length] = source;
                return grown;
            }

            return new Source[] { (Source) existing, source };
        });
    }

    /** Whether a lookup that finds nothing here has to be put to the class loader's own search as well. */
    boolean isIncomplete() {
        return incomplete;
    }

    Source sourceOf(Path entry) {
        return byPath.get(entry.toAbsolutePath().normalize().toString());
    }

    /** The first entry on the path with this name, or {@code null}. */
    Source find(String name) {
        String key = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        Object owner = owners.get(key);
        Source indexed = owner instanceof Source[] several ? several[0] : (Source) owner;

        if (directories.isEmpty()) {
            return indexed;
        }

        for (Source directory : directories) {
            if (indexed != null && directory.ordinal > indexed.ordinal) {
                break;
            }

            if (directory.has(name)) {
                return directory;
            }
        }

        return indexed;
    }

    /** Every entry on the path with this name, in order. */
    List<Source> findAll(String name) {
        String key = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        Object owner = owners.get(key);
        List<Source> out = new ArrayList<>();

        if (owner instanceof Source[] several) {
            out.addAll(List.of(several));
        } else if (owner != null) {
            out.add((Source) owner);
        }

        if (!directories.isEmpty()) {
            for (Source directory : directories) {
                if (directory.has(name)) {
                    out.add(directory);
                }
            }

            out.sort(java.util.Comparator.comparingInt(source -> source.ordinal));
        }

        return out;
    }

    int size() {
        return owners.size();
    }

    /**
     * A jar entry's name as it stands in a URL: most are plain already, and the
     * rest are escaped the way a URI's path is.
     */
    private static String encode(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);

            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || c == '/' || c == '.' || c == '_' || c == '-' || c == '$')) {
                try {
                    return new URI(null, null, "/" + name, null).getRawPath().substring(1);
                } catch (URISyntaxException e) {
                    return name;
                }
            }
        }

        return name;
    }
}
