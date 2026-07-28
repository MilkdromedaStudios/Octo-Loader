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
import studios.milkdromeda.octo.report.LoadingReport;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.transform.PhantomClasses;
import studios.milkdromeda.octo.transform.TransformContext;
import studios.milkdromeda.octo.transform.TransformPipeline;
import studios.milkdromeda.octo.transform.Transformer;
import studios.milkdromeda.octo.util.Failures;
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
            "net.minecraftforge.forgespi.",
            "net.neoforged.fml.", "net.neoforged.bus.", "net.neoforged.api.distmarker.",
            "net.neoforged.neoforgespi.",
            // Maven's version types, because Forge returns them from IModInfo and
            // mods cast between them: two copies is worse than none.
            "org.apache.maven.artifact.versioning.",
            // Mixin has to be the loader's single copy: the transformer that
            // applies a mod's mixins and the annotations that mod was compiled
            // against must be the same classes, or nothing matches.
            "org.spongepowered.asm.", "org.spongepowered.include.", "com.llamalad7.mixinextras.",
            "org.objectweb.asm.", "com.google.gson.", "com.electronwill.nightconfig.");

    /** Loaded from the parent even though they sit under a parent-first prefix. */
    private static final Set<String> PARENT_LAST_EXCEPTIONS = Set.of();

    private final Map<String, TransformPipeline> pipelinesByJar = new HashMap<>();
    private final Map<String, LoadedMod> modsByJar = new HashMap<>();
    private final Map<String, byte[]> phantomCache = new ConcurrentHashMap<>();
    private final PhantomClasses phantoms;
    private final TransformPipeline gamePipeline;
    private final LaunchContext.CompatOptions compat;
    private final LoadingReport report;
    /** Rewrites that apply to everything — the game included — after each mod's own. */
    private volatile TransformPipeline globalPipeline = TransformPipeline.empty();
    /** Mixin, which runs last and is deliberately not part of what mixin itself reads. */
    private volatile Transformer weaver;

    public OctoClassLoader(List<URL> urls, ClassLoader parent, TransformPipeline gamePipeline,
            PhantomClasses phantoms, LaunchContext.CompatOptions compat, LoadingReport report) {
        super("octo", urls.toArray(new URL[0]), parent);
        this.gamePipeline = gamePipeline;
        this.phantoms = phantoms;
        this.compat = compat;
        this.report = report;
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

    /**
     * Attaches a jar that is not a mod but that a mod needs.
     *
     * <p>Jar-in-jar payloads with no metadata are libraries — Create ships
     * Registrate this way, Fabric API ships a dozen — and they belong on the
     * class path without going anywhere near the mod machinery: there is no
     * metadata to resolve, no entrypoint to construct and no lifecycle to
     * dispatch. They pass through the game pipeline like any other class.
     */
    public void addLibrary(Path jar) {
        Path path = jar.toAbsolutePath().normalize();

        try {
            addURL(path.toUri().toURL());
            LOG.debug("added the bundled library {} to the class path", path.getFileName());
        } catch (IOException e) {
            LOG.error("could not add the library {} to the class path: {}", path, e.toString());
        }
    }

    /**
     * Adds a rewrite that every class passes through, whichever jar it came from.
     *
     * <p>Access widening and mixin both work this way: they are not a property of
     * the mod being loaded but of the mods that asked for them, and they have to
     * reach the game's own classes, which belong to no mod at all. Order matters —
     * these run after a mod's own translation, so a mod from 2013 is already
     * speaking today's names by the time a mixin tries to match against it.
     */
    public void addGlobalTransformer(Transformer transformer) {
        globalPipeline = globalPipeline.with(transformer);
    }

    /**
     * Installs the mixin weaver, which runs after everything else.
     *
     * <p>It is held apart from the other global rewrites because mixin has to be
     * able to read a class as it stands <em>before</em> mixins are applied — that
     * is what {@link #rawClassBytes} returns — and folding it into the same
     * pipeline would mean asking mixin to weave the class it is in the middle of
     * weaving.
     */
    public void installWeaver(Transformer transformer) {
        this.weaver = transformer;
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
                try {
                    return super.loadClass(name, resolve);
                } catch (ClassNotFoundException e) {
                    // Octo implements the mod-facing half of four loaders, not the
                    // whole of any of them. A mod reaching for a corner none of the
                    // bridges covers — some FML internal, say — used to fail here
                    // and take the mod with it, even though the reference was one
                    // the mod would never have called. Falling through lets the
                    // stand-in machinery answer instead.
                    Class<?> phantom = definePhantom(name, true);

                    if (phantom == null) {
                        throw e;
                    }

                    if (resolve) {
                        resolveClass(phantom);
                    }

                    return phantom;
                }
            }

            try {
                Class<?> found = findClass(name);

                if (resolve) {
                    resolveClass(found);
                }

                return found;
            } catch (ClassNotFoundException e) {
                try {
                    return super.loadClass(name, resolve);
                } catch (ClassNotFoundException parentMissedItToo) {
                    Class<?> phantom = definePhantom(name, true);

                    if (phantom == null) {
                        throw parentMissedItToo;
                    }

                    if (resolve) {
                        resolveClass(phantom);
                    }

                    return phantom;
                }
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

    /**
     * @param mayInvent whether a loader-API class nobody planned for may be
     *                  declared here. Only true once the parent has been asked
     *                  and come back empty: Octo ships plenty of these classes
     *                  itself, and inventing an empty stand-in for one that
     *                  exists costs the mod the real thing.
     * @return the stand-in for a class nothing on the path has, or {@code null}
     */
    private Class<?> definePhantom(String name, boolean mayInvent) {
        byte[] phantom = phantomFor(name, mayInvent);

        if (phantom == null) {
            return null;
        }

        return defineClass(name, phantom, 0, phantom.length,
                new ProtectionDomain(new CodeSource(null, (Certificate[]) null), null, this, null));
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/') + ".class";
        URL resource = findResource(path);

        if (resource == null) {
            // Planned stand-ins only. Anything invented on demand waits until
            // loadClass has given the parent its turn.
            Class<?> phantom = definePhantom(name, false);

            if (phantom != null) {
                return phantom;
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

        String internalName = name.replace('.', '/');
        TransformContext context = TransformContext.of(mod, this::classExists);
        byte[] transformed = weave(globalPipeline.apply(internalName,
                pipeline.apply(internalName, bytes, context), context), internalName, context);

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

    /** Generates a stand-in for a class a mod expects and this runtime lacks. */
    private byte[] phantomFor(String name, boolean mayInvent) {
        if (!compat.stubMissingApi()) {
            return null;
        }

        String internalName = name.replace('.', '/');
        PhantomClasses.Spec spec = phantoms.spec(internalName);

        if (spec == null && mayInvent) {
            spec = declareLoaderApiStandIn(internalName);
        }

        if (spec == null) {
            return null;
        }

        return phantomCache.computeIfAbsent(internalName, ignored -> {
            LOG.info("standing in for missing class {}", name);
            return phantoms.generate(internalName);
        });
    }

    /**
     * The packages where a class Octo does not have is Octo's gap, not the mod's.
     *
     * <p>A mod referencing a Minecraft class that no longer exists is the time
     * capsule's business and is planned in advance from the mod's bytecode. A mod
     * referencing part of a loader API that Octo simply never implemented has
     * nowhere else to be answered, and refusing it costs the whole mod.
     */
    static final List<String> STAND_IN_PACKAGES = List.of(
            "net/neoforged/", "net/minecraftforge/", "net/fabricmc/", "org/quiltmc/",
            "cpw/mods/fml/", "com/mumfrey/liteloader/");

    private PhantomClasses.Spec declareLoaderApiStandIn(String internalName) {
        // The compatibility table first: it knows whether the class is one mods
        // implement or one they construct, and it is the only thing that may
        // authorise standing in for a class outside the loader packages.
        PhantomClasses.Spec declared = phantoms.declareFromTable(internalName);

        if (declared != null) {
            LOG.warn("this Minecraft has no {} and no equivalent of it; standing in for it "
                    + "so the mod can still load", internalName.replace('/', '.'));
            return declared;
        }

        for (String prefix : STAND_IN_PACKAGES) {
            if (internalName.startsWith(prefix)) {
                LOG.warn("Octo does not implement {}; standing in for it so the mod can still load",
                        internalName.replace('/', '.'));
                return phantoms.declareInterface(internalName);
            }
        }

        return null;
    }

    /**
     * A class's bytes as mixin needs to see them: after the mod's own
     * translation, before any mixin is applied.
     *
     * <p>Mixin reads its target classes and its mixin classes this way rather
     * than loading them, which is what lets it modify a class before the JVM
     * ever sees the original. Feeding it the translated form is what allows a
     * mixin to target a class from a mod built for another Minecraft version.
     *
     * @param internalName e.g. {@code net/minecraft/client/Minecraft}
     * @return the class file, or {@code null} when nothing on the path has it
     */
    public byte[] rawClassBytes(String internalName) {
        String path = internalName + ".class";
        URL resource = findResource(path);

        if (resource == null) {
            ClassLoader parent = getParent();
            resource = parent == null ? null : parent.getResource(path);
        }

        if (resource == null) {
            return null;
        }

        byte[] bytes;
        URL jar;

        try {
            try (InputStream in = resource.openConnection().getInputStream()) {
                bytes = ModSource.readFully(in);
            }

            jar = originOf(resource);
        } catch (IOException e) {
            return null;
        }

        String key = jar == null ? null : key(jar);
        LoadedMod mod = key == null ? null : modsByJar.get(key);
        TransformPipeline pipeline = key == null ? gamePipeline : pipelinesByJar.getOrDefault(key, gamePipeline);

        TransformContext context = TransformContext.of(mod, this::classExists);
        return globalPipeline.apply(internalName, pipeline.apply(internalName, bytes, context), context);
    }

    /**
     * Whether this thread is already inside the weaver.
     *
     * <p>Mixin loads classes of its own while it works — configuration plugins,
     * and whatever those plugins touch — and those loads come straight back
     * here. Calling mixin again from inside itself is the "re-entrance detected
     * during prepare phase, this will cause serious problems" error, and the
     * problems are real: the class that was being woven comes out unwoven, and
     * mods whose mixins silently did not apply fail much later with something
     * that looks unrelated.
     */
    private final ThreadLocal<Boolean> weaving = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Applies mixins, if any mod uses them, and never lets one failing class stop a load. */
    private byte[] weave(byte[] bytes, String internalName, TransformContext context) {
        Transformer current = weaver;

        if (current == null) {
            return bytes;
        }

        if (weaving.get()) {
            // Mixin is asking for this class itself. Handing it back untouched is
            // right: mixin does not weave its own plugins, and it is the only
            // answer that does not re-enter the transformer.
            LOG.debug("not weaving {}: mixin is loading it itself", internalName.replace('/', '.'));
            return bytes;
        }

        weaving.set(Boolean.TRUE);

        try {
            byte[] result = current.transform(internalName, bytes, context);
            return result == null ? bytes : result;
        } catch (NoClassDefFoundError e) {
            // Mixin refuses to hand back a class that lives in a mod's declared
            // mixin package, because loading one directly means the mod has a
            // bug. That refusal is the useful answer, so it is passed on rather
            // than papered over with the untransformed class.
            throw e;
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            String className = internalName.replace('/', '.');
            LOG.error("mixins for {} could not be applied: {}", className, Failures.describe(e));
            OctoLog.detail(e);

            // Handing this back unwoven is a real loss — the mod that owns the
            // mixin runs, the change it exists to make does not, and what breaks
            // is usually some other mod much later. So the game still starts,
            // but the mod is named in the report rather than left to be guessed.
            // A warning, not an error: the mod is running, it is just missing
            // the one change this mixin made. Listing it as "not running" next
            // to a mod that genuinely did not load makes both harder to read.
            String culprit = culpritOf(e);
            report.warning(culprit, "one of its mixins does not fit this version of "
                    + className.replace('/', '.') + ", so that part of it is inactive", Failures.describe(e));

            if (compat.strict()) {
                throw new ModLoadingException(culprit + ": mixins could not be applied to " + className,
                        List.of(Failures.describe(e)), e);
            }

            return bytes;
        } finally {
            weaving.set(Boolean.FALSE);
        }
    }

    /**
     * Names the mod whose mixin config mixin was complaining about.
     *
     * <p>"mixins could not be applied to net.minecraft.SharedConstants" names
     * the victim, and the victim is never the thing to go and fix. Mixin's own
     * message usually contains the offending config's file name, which is
     * enough to get from there to a mod id.
     */
    private static String culpritOf(Throwable error) {
        String owner = studios.milkdromeda.octo.mixin.MixinSupport
                .ownerOfConfigIn(Failures.describe(error));
        return owner == null ? "a mod" : owner;
    }

    /** Whether this loader has already defined a class, without trying to load it. */
    public boolean isClassLoaded(String binaryName) {
        return findLoadedClass(binaryName) != null;
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
