package studios.milkdromeda.octo.launch;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import studios.milkdromeda.octo.bridge.BridgeRegistry;
import studios.milkdromeda.octo.bridge.LoaderBridge;
import studios.milkdromeda.octo.compat.Era;
import studios.milkdromeda.octo.compat.TimeCapsule;
import studios.milkdromeda.octo.compat.mapping.MappingRegistry;
import studios.milkdromeda.octo.discovery.ModDiscoverer;
import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.resolve.ModResolver;
import studios.milkdromeda.octo.resolve.Resolution;
import studios.milkdromeda.octo.runtime.Lifecycle;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.transform.PhantomClasses;
import studios.milkdromeda.octo.transform.TransformPipeline;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Starts the game with every mod in the folder running, whatever they were built for.
 *
 * <p>The sequence is deliberately the same for all four ecosystems: discover,
 * resolve, translate, load, construct, then walk the lifecycle. Only the last
 * two steps consult the mod's format, and only through a bridge.
 */
public final class OctoLauncher {
    private static final OctoLog LOG = OctoLog.of(OctoLauncher.class);

    private final LaunchContext context;
    private final BridgeRegistry bridges;

    public OctoLauncher(LaunchContext context) {
        this(context, new BridgeRegistry());
    }

    public OctoLauncher(LaunchContext context, BridgeRegistry bridges) {
        this.context = context;
        this.bridges = bridges;
    }

    /** Runs everything up to, but not including, the game's own main method. */
    public LaunchResult load() {
        LOG.info("Octo Loader {} starting for Minecraft {} ({})", OctoRuntime.VERSION,
                context.minecraftVersion().isEmpty() ? "unknown" : context.minecraftVersion(), context.side());

        OctoRuntime runtime = OctoRuntime.initialise(context);
        TimeCapsule timeCapsule = new TimeCapsule(context);
        installMappings(timeCapsule.runtimeEra());

        List<ModCandidate> candidates = new ModDiscoverer(context.octoDir().resolve("nested"))
                .discover(context.modDirs());
        LOG.info("found {} mod(s) in {}", candidates.size(), context.modDirs());

        Resolution resolution = new ModResolver(context).resolve(candidates);

        for (Resolution.Problem problem : resolution.problems()) {
            if (problem.fatal()) {
                LOG.warn("{}", problem);
            } else {
                LOG.info("{}", problem);
            }
        }

        if (context.compat().failOnUnloadableMod() && !resolution.fatalProblems().isEmpty()) {
            throw new IllegalStateException("refusing to start: " + resolution.fatalProblems());
        }

        List<LoadedMod> mods = new ArrayList<>();

        for (ModCandidate candidate : resolution.order()) {
            LoadedMod mod = new LoadedMod(candidate);
            candidate.notes().forEach(mod::recordFix);
            runtime.register(mod);
            mods.add(mod);
        }

        PhantomClasses phantoms = new PhantomClasses();
        OctoClassLoader classLoader = new OctoClassLoader(gameUrls(), getClass().getClassLoader(),
                TransformPipeline.empty(), phantoms, context.compat().stubMissingApi());

        for (LoadedMod mod : mods) {
            classLoader.addMod(mod, timeCapsule.pipelineFor(mod));
        }

        if (context.compat().stubMissingApi()) {
            planPhantoms(mods, phantoms, classLoader, timeCapsule);
        }

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(classLoader);

        try {
            construct(mods, classLoader);

            for (Lifecycle phase : Lifecycle.values()) {
                dispatch(phase, mods);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        List<LoadedMod> failed = mods.stream().filter(LoadedMod::isFailed).toList();
        List<LoadedMod> loaded = mods.stream().filter(mod -> !mod.isFailed()).toList();

        LOG.info("{} mod(s) ready{}", loaded.size(), failed.isEmpty() ? "" : ", " + failed.size() + " failed");
        return new LaunchResult(runtime, resolution, classLoader, loaded, failed);
    }

    /**
     * Loads the loader, then starts the game.
     *
     * @return what was loaded, once the game's main method returns
     */
    public LaunchResult launch() throws Exception {
        LaunchResult result = load();
        String mainClass = context.mainClass();

        if (mainClass == null || mainClass.isBlank()) {
            LOG.warn("no main class was given, so the game was not started ({})", result.summary());
            return result;
        }

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(result.classLoader());

        try {
            Class<?> type = Class.forName(mainClass, false, result.classLoader());
            Method main = type.getMethod("main", String[].class);
            LOG.info("handing over to {}", mainClass);
            main.invoke(null, (Object) context.launchArguments().toArray(new String[0]));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        return result;
    }

    private void installMappings(Era runtimeEra) {
        Path directory = context.octoDir().resolve("mappings");
        MappingRegistry registry = MappingRegistry.load(directory, MappingRegistry.namespaceOf(runtimeEra));
        MappingRegistry.install(registry);

        if (registry.isEmpty()) {
            LOG.debug("no mapping tables in {}; old mods will be translated by API rules alone", directory);
        }
    }

    private List<URL> gameUrls() {
        List<URL> urls = new ArrayList<>();

        for (Path jar : context.gameJars()) {
            if (!Files.exists(jar)) {
                LOG.warn("game jar {} does not exist", jar);
                continue;
            }

            try {
                urls.add(jar.toUri().toURL());
            } catch (MalformedURLException e) {
                LOG.warn("could not use game jar {}: {}", jar, e.toString());
            }
        }

        return urls;
    }

    /**
     * Reads the classes of every mod older than the runtime, so the loader knows
     * in advance which of the classes they reference no longer exist and what
     * shape a stand-in would have to be.
     */
    private void planPhantoms(List<LoadedMod> mods, PhantomClasses phantoms, OctoClassLoader classLoader,
            TimeCapsule timeCapsule) {
        for (LoadedMod mod : mods) {
            if (!mod.era().isKnown() || !mod.era().isOlderThan(timeCapsule.runtimeEra())) {
                continue;
            }

            TransformPipeline pipeline = timeCapsule.pipelineFor(mod);

            try (ModSource source = ModSource.open(mod.effectivePath())) {
                for (String entry : source.entries()) {
                    if (!entry.endsWith(".class")) {
                        continue;
                    }

                    byte[] bytes = source.read(entry).orElse(null);

                    if (bytes == null) {
                        continue;
                    }

                    // Look at the class as it will be after translation, not as
                    // it is on disk: remapping resolves most missing references.
                    byte[] translated = pipeline.apply(entry.substring(0, entry.length() - 6), bytes,
                            studios.milkdromeda.octo.transform.TransformContext.of(null, classLoader::classExists));
                    phantoms.observe(translated, classLoader::classExists);
                }
            } catch (IOException | RuntimeException e) {
                LOG.debug("could not scan {} for missing references: {}", mod.id(), e.toString());
            }
        }

        if (!phantoms.specs().isEmpty()) {
            LOG.info("{} class(es) referenced by old mods no longer exist; stand-ins are ready",
                    phantoms.specs().size());
        }
    }

    private void construct(List<LoadedMod> mods, OctoClassLoader classLoader) {
        for (LoadedMod mod : mods) {
            LoaderBridge bridge = bridges.forFormat(mod.format());

            if (bridge == null) {
                LOG.warn("{}: no bridge handles {} mods", mod.id(), mod.format());
                continue;
            }

            try {
                bridge.construct(mod, classLoader);
            } catch (Exception | LinkageError e) {
                LOG.error("{}: construction failed: {}", mod.id(), e.toString());
                mod.fail(e);
            }
        }
    }

    private void dispatch(Lifecycle phase, List<LoadedMod> mods) {
        OctoRuntime.get().phase(phase);

        for (LoadedMod mod : mods) {
            if (mod.isFailed()) {
                continue;
            }

            LoaderBridge bridge = bridges.forFormat(mod.format());

            if (bridge == null) {
                continue;
            }

            try {
                bridge.dispatch(phase, mod);
            } catch (Exception | LinkageError e) {
                LOG.error("{}: {} failed: {}", mod.id(), phase, e.toString());
                mod.fail(e);
            }
        }
    }
}
