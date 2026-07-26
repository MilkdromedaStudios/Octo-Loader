package studios.milkdromeda.octo.launch;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.transform.PhantomClasses;
import studios.milkdromeda.octo.transform.TransformContext;
import studios.milkdromeda.octo.transform.TransformPipeline;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * The class loader the game and every mod run inside.
 *
 * <p>It is parent-last, because a mod's classes have to be able to shadow what
 * is on the system class path, and because each mod's classes need to pass
 * through that mod's own transformer pipeline on the way in — a 1.7.10 mod and
 * a current one are loaded from the same folder by the same loader, but only
 * one of them gets remapped.
 *
 * <p>The exceptions are the loader itself and the four ecosystems' APIs, which
 * are delegated to the parent so that every mod shares one
 * {@code FabricLoader}, one {@code ModList}, and one runtime.
 */
public final class OctoClassLoader extends URLClassLoader {
    private static final OctoLog LOG = OctoLog.of(OctoClassLoader.class);

    static {
        registerAsParallelCapable();
    }

    /** Packages that must resolve to the single copy the loader holds. */
    private static final List<String> PARENT_FIRST_PREFIXES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "org.w3c.", "org.xml.",
            "studios.milkdromeda.octo.",
            "net.fabricmc.api.", "net.fabricmc.loader.",
            "org.quiltmc.loader.", "org.quiltmc.qsl.base.api.entrypoint.",
            "net.minecraftforge.fml.", "net.minecraftforge.eventbus.", "net.minecraftforge.api.distmarker.",
            "net.neoforged.fml.", "net.neoforged.bus.", "net.neoforged.api.distmarker.",
            "org.objectweb.asm.", "com.google.gson.", "com.electronwill.nightconfig.");

    /** Loaded from the parent even though they sit under a parent-first prefix. */
    private static final Set<String> PARENT_LAST_EXCEPTIONS = Set.of();

    private final Map<String, TransformPipeline> pipelinesByJar = new HashMap<>();
    private final Map<String, LoadedMod> modsByJar = new HashMap<>();
    private final Map<String, byte[]> phantomCache = new ConcurrentHashMap<>();
    private final PhantomClasses phantoms;
    private final TransformPipeline gamePipeline;
    private final boolean stubMissingApi;

    public OctoClassLoader(List<URL> urls, ClassLoader parent, TransformPipeline gamePipeline,
            PhantomClasses phantoms, boolean stubMissingApi) {
        super("octo", urls.toArray(new URL[0]), parent);
        this.gamePipeline = gamePipeline;
        this.phantoms = phantoms;
        this.stubMissingApi = stubMissingApi;
    }

    /** Attaches a mod's jar and the pipeline its classes must pass through. */
    public void addMod(LoadedMod mod, TransformPipeline pipeline) {
        Path path = mod.effectivePath().toAbsolutePath().normalize();

        try {
            addURL(path.toUri().toURL());
        } catch (IOException e) {
            LOG.error("could not add {} to the class path: {}", path, e.toString());
            return;
        }

        String key = key(path);
        modsByJar.put(key, mod);

        if (!pipeline.isEmpty()) {
            pipelinesByJar.put(key, pipeline);
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);

            if (loaded != null) {
                if (resolve) {
                    resolveClass(loaded);
                }

                return loaded;
            }

            if (isParentFirst(name)) {
                return super.loadClass(name, resolve);
            }

            try {
                Class<?> found = findClass(name);

                if (resolve) {
                    resolveClass(found);
                }

                return found;
            } catch (ClassNotFoundException e) {
                return super.loadClass(name, resolve);
            }
        }
    }

    private static boolean isParentFirst(String name) {
        if (PARENT_LAST_EXCEPTIONS.contains(name)) {
            return false;
        }

        for (String prefix : PARENT_FIRST_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";
        URL resource = findResource(path);

        if (resource == null) {
            byte[] phantom = phantomFor(name);

            if (phantom != null) {
                return defineClass(name, phantom, 0, phantom.length,
                        new ProtectionDomain(new CodeSource(null, (Certificate[]) null), null, this, null));
            }

            throw new ClassNotFoundException(name);
        }

        byte[] bytes;
        URL jar;

        try {
            URLConnection connection = resource.openConnection();

            try (InputStream in = connection.getInputStream()) {
                bytes = ModSource.readFully(in);
            }

            jar = originOf(resource);
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }

        String key = jar == null ? null : key(jar);
        LoadedMod mod = key == null ? null : modsByJar.get(key);
        TransformPipeline pipeline = key == null ? gamePipeline : pipelinesByJar.getOrDefault(key, gamePipeline);

        byte[] transformed = pipeline.apply(name.replace('.', '/'), bytes,
                TransformContext.of(mod, this::classExists));

        definePackageFor(name, jar);

        CodeSource codeSource = new CodeSource(jar, (Certificate[]) null);
        return defineClass(name, transformed, 0, transformed.length,
                new ProtectionDomain(codeSource, null, this, null));
    }

    private void definePackageFor(String className, URL jar) {
        int lastDot = className.lastIndexOf('.');

        if (lastDot <= 0) {
            return;
        }

        String packageName = className.substring(0, lastDot);

        if (getDefinedPackage(packageName) == null) {
            try {
                definePackage(packageName, null, null, null, null, null, null, null);
            } catch (IllegalArgumentException ignored) {
                // Another thread won the race; that is fine.
            }
        }
    }

    /** Generates a stand-in for a class an old mod expects and this runtime lacks. */
    private byte[] phantomFor(String name) {
        if (!stubMissingApi) {
            return null;
        }

        String internalName = name.replace('.', '/');
        PhantomClasses.Spec spec = phantoms.spec(internalName);

        if (spec == null) {
            return null;
        }

        return phantomCache.computeIfAbsent(internalName, ignored -> {
            LOG.info("standing in for missing class {}", name);
            return phantoms.generate(internalName);
        });
    }

    /** Whether a class can be resolved at all, without initialising it. */
    public boolean classExists(String internalName) {
        String binaryName = internalName.replace('/', '.');

        if (findLoadedClass(binaryName) != null) {
            return true;
        }

        if (findResource(internalName + ".class") != null) {
            return true;
        }

        ClassLoader parent = getParent();
        return parent != null && parent.getResource(internalName + ".class") != null;
    }

    /** {@code jar:file:/mods/x.jar!/com/example/Foo.class} to {@code file:/mods/x.jar}. */
    private static URL originOf(URL resource) {
        String text = resource.toString();

        if (!text.startsWith("jar:")) {
            return null;
        }

        int separator = text.indexOf("!/");

        if (separator < 0) {
            return null;
        }

        try {
            return new java.net.URI(text.substring(4, separator)).toURL();
        } catch (Exception e) {
            return null;
        }
    }

    private static String key(URL jar) {
        try {
            return key(Path.of(jar.toURI()));
        } catch (Exception e) {
            return jar.toString();
        }
    }

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    /** The mods attached to this loader, in the order they were added. */
    public Map<String, LoadedMod> mods() {
        return new LinkedHashMap<>(modsByJar);
    }
}
