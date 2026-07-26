package studios.milkdromeda.octo.testkit;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Builds a jar on disk from compiled classes and text entries. */
public final class JarBuilder {
    private final Map<String, byte[]> entries = new LinkedHashMap<>();

    public JarBuilder file(String path, String content) {
        entries.put(path, content.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    public JarBuilder file(String path, byte[] content) {
        entries.put(path, content);
        return this;
    }

    /** Adds compiled classes, keyed by binary name. */
    public JarBuilder classes(Map<String, byte[]> compiled) {
        compiled.forEach((name, bytes) -> entries.put(name.replace('.', '/') + ".class", bytes));
        return this;
    }

    /** Adds only the classes whose binary name starts with {@code prefix}. */
    public JarBuilder classes(Map<String, byte[]> compiled, String prefix) {
        compiled.forEach((name, bytes) -> {
            if (name.startsWith(prefix)) {
                entries.put(name.replace('.', '/') + ".class", bytes);
            }
        });

        return this;
    }

    public Path writeTo(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());

        try (OutputStream out = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }

        return jar;
    }
}
