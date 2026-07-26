package studios.milkdromeda.octo.testkit;

import java.util.HashMap;
import java.util.Map;

/** Loads classes from a map of bytecode, so transformed classes can be run. */
public final class ByteClassLoader extends ClassLoader {
    private final Map<String, byte[]> classes = new HashMap<>();

    public ByteClassLoader(ClassLoader parent) {
        super(parent);
    }

    public ByteClassLoader define(String binaryName, byte[] bytes) {
        classes.put(binaryName, bytes);
        return this;
    }

    public ByteClassLoader defineAll(Map<String, byte[]> compiled) {
        classes.putAll(compiled);
        return this;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = classes.get(name);

        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }

        return defineClass(name, bytes, 0, bytes.length);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);

            if (loaded == null) {
                // Classes under test come first, so a transformed copy wins over
                // the original on the test class path.
                loaded = classes.containsKey(name) ? findClass(name) : super.loadClass(name, false);
            }

            if (resolve) {
                resolveClass(loaded);
            }

            return loaded;
        }
    }

    public boolean has(String binaryName) {
        return classes.containsKey(binaryName);
    }
}
