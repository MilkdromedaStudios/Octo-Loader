package studios.milkdromeda.octo.testkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Compiles Java source in memory.
 *
 * <p>The tests need real mod jars — real bytecode, real annotations, real
 * references to classes that will not exist at run time. Writing that by hand in
 * ASM would test the fixture rather than the loader, so the fixtures are
 * compiled from source with the JDK's own compiler.
 */
public final class SourceCompiler {
    private SourceCompiler() {
    }

    /**
     * @param sources    class name to source text
     * @param classpath  extra entries, e.g. a directory of previously compiled stubs
     * @return class name to bytecode, including any nested classes produced
     */
    public static Map<String, byte[]> compile(Map<String, String> sources, List<String> classpath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        if (compiler == null) {
            throw new IllegalStateException("these tests need a JDK, not a JRE");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, null, null);
        InMemoryFileManager fileManager = new InMemoryFileManager(standard);

        List<JavaFileObject> units = new ArrayList<>();
        sources.forEach((name, source) -> units.add(new SourceFile(name, source)));

        // The loader's own classes are always on the compile path, since fixtures
        // implement ModInitializer, carry @Mod, and so on.
        List<String> entries = new ArrayList<>(classpath);
        entries.add(System.getProperty("java.class.path"));

        List<String> options = new ArrayList<>(List.of("-nowarn", "-proc:none",
                "-classpath", String.join(java.io.File.pathSeparator, entries)));

        boolean success = compiler.getTask(null, fileManager, diagnostics, options, null, units).call();

        if (!success) {
            StringBuilder message = new StringBuilder("compilation failed:");
            diagnostics.getDiagnostics().forEach(diagnostic -> message.append("\n  ").append(diagnostic));
            throw new IllegalStateException(message.toString());
        }

        try {
            // Output only reaches the map on flush, and the compiler does not
            // flush a file manager it did not open.
            fileManager.close();
        } catch (IOException e) {
            throw new IllegalStateException("could not collect compiled classes", e);
        }

        return fileManager.classes;
    }

    public static Map<String, byte[]> compile(Map<String, String> sources) {
        return compile(sources, List.of());
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;

        SourceFile(String className, String source) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }

    private static final class ClassFile extends SimpleJavaFileObject {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        ClassFile(String className) {
            super(URI.create("bytes:///" + className.replace('.', '/') + Kind.CLASS.extension), Kind.CLASS);
        }

        @Override
        public ByteArrayOutputStream openOutputStream() {
            return bytes;
        }
    }

    private static final class InMemoryFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, byte[]> classes = new LinkedHashMap<>();
        private final Map<String, ClassFile> pending = new LinkedHashMap<>();

        InMemoryFileManager(StandardJavaFileManager delegate) {
            super(delegate);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind,
                FileObject sibling) {
            ClassFile file = new ClassFile(className);
            pending.put(className, file);
            return file;
        }

        @Override
        public void close() throws IOException {
            flush();
            super.close();
        }

        @Override
        public void flush() throws IOException {
            pending.forEach((name, file) -> classes.put(name, file.openOutputStream().toByteArray()));
            super.flush();
        }
    }
}
