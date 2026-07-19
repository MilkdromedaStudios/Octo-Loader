package studios.milkdromeda.octoloader;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Compiles small fixture sources at test runtime against the test classpath. */
public final class TestCompiler {
    private TestCompiler() {
    }

    /**
     * Compiles {@code source} (declaring {@code className}, dot-separated) and
     * returns a map of class-file jar entry name → bytes, including any inner
     * classes the source produced.
     */
    public static java.util.Map<String, byte[]> compile(Path workDir, String className, String source)
            throws IOException {
        Path sourceFile = workDir.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);
        Path classesDir = workDir.resolve("classes");
        Files.createDirectories(classesDir);

        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-cp", System.getProperty("java.class.path"),
                "-d", classesDir.toString(),
                sourceFile.toString());
        if (result != 0) throw new AssertionError("fixture compilation failed for " + className);

        java.util.Map<String, byte[]> classes = new java.util.LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(classesDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                classes.put(classesDir.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
            }
        }
        return classes;
    }
}
