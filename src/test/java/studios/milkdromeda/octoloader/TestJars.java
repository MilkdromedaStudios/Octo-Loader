package studios.milkdromeda.octoloader;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Builds small in-memory mod jars for tests. */
public final class TestJars {
    private TestJars() {
    }

    public static Path jar(Path dir, String name, Map<String, String> entries) throws IOException {
        Map<String, byte[]> binary = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            binary.put(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return binaryJar(dir, name, binary);
    }

    public static Path binaryJar(Path dir, String name, Map<String, byte[]> entries) throws IOException {
        Path file = dir.resolve(name);
        try (OutputStream fileOut = Files.newOutputStream(file);
             JarOutputStream out = new JarOutputStream(fileOut)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return file;
    }
}
