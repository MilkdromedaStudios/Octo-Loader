package studios.milkdromeda.octo.launch;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import studios.milkdromeda.octo.access.AccessRuleLoader;
import studios.milkdromeda.octo.access.AccessRuleTransformer;
import studios.milkdromeda.octo.access.AccessRules;
import studios.milkdromeda.octo.bridge.BridgeRegistry;
import studios.milkdromeda.octo.bridge.LoaderBridge;
import studios.milkdromeda.octo.compat.Era;
import studios.milkdromeda.octo.compat.TimeCapsule;
import studios.milkdromeda.octo.compat.mapping.MappingRegistry;
import studios.milkdromeda.octo.discovery.ModDiscoverer;
import studios.milkdromeda.octo.hook.GameHooks;
import studios.milkdromeda.octo.mixin.MixinSupport;
import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.resolve.ModResolver;
import studios.milkdromeda.octo.resolve.Resolution;
import studios.milkdromeda.octo.runtime.Lifecycle;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.transform.PhantomClasses;
import studios.milkdromeda.octo.transform.TransformPipeline;
import studios.milkdromeda.octo.transform.Transformer;
import studios.milkdromeda.octo.util.Failures;
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

    /**
     * Runs everything up to, but not including, the game's own main method.
     *
     * <p>Whatever happens in here, the caller gets a class loader it can start
     * Minecraft with. Mod loading is the part that can go wrong — a corrupt jar,
     * a mod that throws where nothing should — and the player's game is not the
     * right thing to spend on that. A failure costs the mods and says so;
     * {@code -Docto.strict=true} restores the older behaviour of refusing to
     * start, for anyone who would rather know immediately.
     */
    public LaunchResult load() {
        LOG.info("Octo Loader {} starting for Minecraft {} ({})", OctoRuntime.VERSION,
                context.minecraftVersion().isEmpty() ? "unknown" : context.minecraftVersion(), context.side());

        OctoRuntime runtime = OctoRuntime.initialise(context);
        PhantomClasses phantoms = new PhantomClasses();
        OctoClassLoader classLoader = new OctoClassLoader(gameUrls(), getClass().getClassLoader(),
                TransformPipeline.empty(), phantoms, context.compat().stubMissingApi());

        if (Boolean.getBoolean("octo.safeMode")) {
            LOG.warn("safe mode: Minecraft will start with no mods at all");
            return new LaunchResult(runtime, new Resolution(), classLoader, List.of(), List.of());
        }

        try {
            return loadMods(runtime, classLoader, phantoms);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);

            if (context.compat().failOnUnloadableMod()) {
                throw e instanceof RuntimeException runtime2 ? runtime2 : new IllegalStateException(e);
            }

            LOG.error("mod loading failed, so Minecraft is starting without mods: {}", Failures.describe(e));
            OctoLog.detail(e);
            LOG.error("the full trace is in logs/octo-loader.log; -Docto.safeMode=true skips mod loading entirely");
            return new LaunchResult(runtime, new Resolution(), classLoader, List.of(), List.of());
        }
    }

    private LaunchResult loadMods(OctoRuntime runtime, OctoClassLoader classLoader, PhantomClasses phantoms) {
        TimeCapsule timeCapsule = new TimeCapsule(context);
        installMappings(timeCapsule.runtimeEra());

        List<ModCandidate> candidates = new ModDiscoverer(context.octoDir().resolve("nested"))
                .discover(context.modDirs());
        LOG.info("found {} mod(s) in {}", candidates.size(), context.modDirs());

        Resolution resolution = new ModResolver(context).resolve(candidates);

        report(resolution);

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

        for (LoadedMod mod : mods) {
            classLoader.addMod(mod, timeCapsule.pipelineFor(mod));
        }

        // All three of these rewrite the game itself on behalf of the mods, so they
        // are installed before a single class is loaded and apply to every jar.
        installAccessRules(mods, classLoader);
        installMixins(mods, classLoader);

        if (context.compat().stubMissingApi()) {
            planPhantoms(mods, phantoms, classLoader, timeCapsule);
        }

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(classLoader);

        try {
            // Before any mod runs, not after: a mod's initialiser is entitled to
            // register a block or read a tag, and both need the game's registries
            // to exist.
            GameBootstrap.run(classLoader);

            construct(mods, classLoader);

            // The client phases belong inside the game's own construction, so
            // they are handed to the hook when the hook can reach them.
            List<Lifecycle> deferred = deferredPhases(classLoader);

            for (Lifecycle phase : Lifecycle.values()) {
                if (!deferred.contains(phase)) {
                    dispatch(phase, mods);
                }
            }

            if (!deferred.isEmpty()) {
                LOG.info("holding {} until the game has started", describe(deferred));
                GameHooks.arm(() -> deferred.forEach(phase -> dispatch(phase, mods)));
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        List<LoadedMod> failed = mods.stream().filter(LoadedMod::isFailed).toList();
        List<LoadedMod> loaded = mods.stream().filter(mod -> !mod.isFailed()).toList();

        LOG.info("{} mod(s) ready{}", loaded.size(), failed.isEmpty() ? "" : ", " + failed.size() + " failed");

        // Named individually, because "3 failed" at the end of a two-hundred-line
        // startup log is not something anyone can act on.
        for (LoadedMod mod : failed) {
            LOG.warn("{} did not load: {}", mod.id(), Failures.describe(mod.failure()));
        }

        // And the ones that did, so "are my mods actually loading?" is a question
        // the log answers rather than one the player has to infer from the game.
        if (!loaded.isEmpty()) {
            LOG.info("running: {}", loaded.stream().map(LoadedMod::id).sorted().collect(Collectors.joining(", ")));
        }

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
            // If the game came and went without the hook firing, the mods that
            // were waiting on it should still get their turn — a server run, or a
            // client whose entry point Octo did not recognise.
            GameHooks.runIfPending("the game's main method returned");
            Thread.currentThread().setContextClassLoader(previous);
        }

        return result;
    }

    /**
     * The phases to hold back until the game calls in.
     *
     * <p>Only on a client, and only when the class the hook attaches to is
     * actually present: with nothing to attach to there would be no callback, and
     * mods that never get initialised are worse than mods initialised early.
     */
    private List<Lifecycle> deferredPhases(OctoClassLoader classLoader) {
        if (context.side() != Side.CLIENT || !classLoader.classExists("net/minecraft/client/Minecraft")) {
            return List.of();
        }

        return List.of(Lifecycle.SIDED_SETUP, Lifecycle.INTER_MOD, Lifecycle.LOAD_COMPLETE);
    }

    private static String describe(List<Lifecycle> phases) {
        return phases.stream().map(Enum::name).collect(Collectors.joining(", "));
    }

    /**
     * Prints what resolution found, without printing it fifty times.
     *
     * <p>A modern mod folder produces one "built for another loader version" note
     * per Fabric API module and one "found more than once" note per nested
     * library — sixty lines that say two things. Anything that stopped a mod
     * loading is still named individually; the rest is counted, and the detail is
     * in the log at debug.
     */
    private void report(Resolution resolution) {
        for (Resolution.Problem problem : resolution.fatalProblems()) {
            LOG.warn("{}", problem);
        }

        summarise(resolution.problems(Resolution.Kind.DUPLICATE),
                "supplied more than once; the newest of each was kept");
        summarise(resolution.problems(Resolution.Kind.PLATFORM_VERSION),
                "declare a Minecraft or loader version other than this one; loaded anyway");

        for (Resolution.Problem problem : resolution.problems(Resolution.Kind.GENERAL)) {
            LOG.info("{}", problem);
        }
    }

    private void summarise(List<Resolution.Problem> problems, String description) {
        if (problems.isEmpty()) {
            return;
        }

        LOG.info("{} mod(s) {}", problems.size(), description);
        problems.forEach(problem -> LOG.debug("{}", problem));
    }

    /**
     * Opens up the parts of the game the mods were compiled against.
     *
     * <p>Fabric mods carry {@code .accesswidener} files and Forge mods carry
     * {@code accesstransformer.cfg}; until now Octo read both and applied
     * neither, which is why a mod that reaches a private game method got an
     * {@code IllegalAccessError} at the first call and one that subclasses a
     * final game class failed to link at all.
     */
    private void installAccessRules(List<LoadedMod> mods, OctoClassLoader classLoader) {
        AccessRules rules = AccessRuleLoader.collect(mods);

        if (!rules.isEmpty()) {
            classLoader.addGlobalTransformer(new AccessRuleTransformer(rules));
        }
    }

    /**
     * Starts mixin over whichever mods use it.
     *
     * <p>Mixin is not optional for a modern mod folder — Sodium, Create, Iris and
     * the whole Fabric API are written as mixins — and a loader that runs their
     * initialisers without applying their mixins gives them a game that is
     * missing exactly the methods they are about to call.
     */
    private void installMixins(List<LoadedMod> mods, OctoClassLoader classLoader) {
        Transformer weaver = MixinSupport.bootstrap(classLoader, mods, context.side());

        if (weaver != null) {
            classLoader.installWeaver(weaver);
        }
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
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
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
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.error("{}: construction failed, skipping this mod: {}", mod.id(), Failures.describe(e));
                OctoLog.detail(e);
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
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.error("{}: {} failed, skipping this mod: {}", mod.id(), phase, Failures.describe(e));
                OctoLog.detail(e);
                mod.fail(e);
            }
        }
    }
}
