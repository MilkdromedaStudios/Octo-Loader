package studios.milkdromeda.octo.launch;

import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import studios.milkdromeda.octo.access.AccessRuleLoader;
import studios.milkdromeda.octo.access.AccessRuleTransformer;
import studios.milkdromeda.octo.access.AccessRules;
import studios.milkdromeda.octo.bridge.BridgeRegistry;
import studios.milkdromeda.octo.bridge.LoaderBridge;
import studios.milkdromeda.octo.bridge.forge.ForgeBuses;
import studios.milkdromeda.octo.compat.Era;
import studios.milkdromeda.octo.compat.TimeCapsule;
import studios.milkdromeda.octo.compat.api.Equivalents;
import studios.milkdromeda.octo.compat.mapping.MappingRegistry;
import studios.milkdromeda.octo.discovery.Discovery;
import studios.milkdromeda.octo.discovery.ModDiscoverer;
import studios.milkdromeda.octo.hook.GameHooks;
import studios.milkdromeda.octo.mixin.MixinSupport;
import studios.milkdromeda.octo.mixin.MixinTargets;
import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.report.GameWatch;
import studios.milkdromeda.octo.report.LoadingReport;
import studios.milkdromeda.octo.report.ReportWindow;
import studios.milkdromeda.octo.resolve.ModResolver;
import studios.milkdromeda.octo.resolve.Resolution;
import studios.milkdromeda.octo.runtime.Lifecycle;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.transform.ByteScan;
import studios.milkdromeda.octo.transform.MissingClassTransformer;
import studios.milkdromeda.octo.transform.MissingMemberTransformer;
import studios.milkdromeda.octo.transform.PhantomClasses;
import studios.milkdromeda.octo.transform.TransformPipeline;
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
     * <p>Minecraft starts. Whatever happens here — a corrupt jar, metadata that
     * will not parse, a mod that throws where nothing should — the caller gets a
     * class loader it can hand the game to, and every failure along the way is
     * collected into a {@link LoadingReport} that is written to disk, printed to
     * the log, and put on screen as the game boots.
     *
     * <p>Both of the other answers have been tried here and both are worse.
     * Logging a line and carrying on is invisible: the player gets a game that
     * looks fine with nothing in it. Refusing to start is visible but useless: a
     * folder of forty mods on a Minecraft newer than any of them will always
     * have something to complain about, and almost none of it is a reason to be
     * unable to play. {@code -Docto.strict=true} still refuses, for automation
     * and for anyone who wants the launch to fail a build.
     */
    public LaunchResult load() {
        LOG.info("Octo Loader {} starting for Minecraft {} ({})", OctoRuntime.VERSION,
                context.minecraftVersion().isEmpty() ? "unknown" : context.minecraftVersion(), context.side());

        OctoRuntime runtime = OctoRuntime.initialise(context);
        PhantomClasses phantoms = new PhantomClasses();
        OctoClassLoader classLoader = new OctoClassLoader(gameUrls(), getClass().getClassLoader(),
                TransformPipeline.empty(), phantoms, context.compat(), report);

        if (Boolean.getBoolean("octo.safeMode")) {
            LOG.warn("safe mode: Minecraft will start with no mods at all");
            return new LaunchResult(runtime, new Resolution(), classLoader, List.of(), List.of());
        }

        try {
            LaunchResult result = loadMods(runtime, classLoader, phantoms);
            publish();
            return result;
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            OctoLog.detail(e);

            // Mod loading fell over somewhere it could not recover from. The
            // game still starts, because a Minecraft with no mods is worth more
            // than no Minecraft, and the reason is on screen either way.
            record(LoadingReport.Severity.ERROR, "Octo", "mod loading stopped early, so no mods are running",
                    Failures.describe(e));
            LOG.error("mod loading failed, so Minecraft is starting without mods: {}", Failures.describe(e));

            if (context.compat().strict()) {
                throw e instanceof ModLoadingException refusal ? refusal
                        : new ModLoadingException("Octo could not load your mods: " + Failures.describe(e),
                                List.of("The full stack trace is in " + logFile() + "."), e);
            }

            publish();
            return new LaunchResult(runtime, new Resolution(), classLoader, List.of(), List.of());
        }
    }

    /**
     * Writes the report out and puts it on screen, once, at the end of loading.
     *
     * <p>Deliberately the last thing before the game is handed control: the
     * window is not modal and does not hold the launch up, so what the player
     * sees is Minecraft starting with a panel next to it saying what is missing.
     */
    private void publish() {
        if (report.isEmpty()) {
            LOG.info("no mod loading problems to report");
            return;
        }

        report.render().lines().forEach(line -> LOG.warn("{}", line));
        Path reportFile = writeReport();

        if (context.side() == Side.CLIENT && !Boolean.getBoolean("octo.noReportWindow")) {
            ReportWindow.show(report, reportFile == null ? logFile() : reportFile);
        }
    }

    private Path writeReport() {
        Path file = context.octoDir().resolve("logs").resolve("mod-report.txt");

        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, report.render());
            LOG.info("the mod report is in {}", file);
            return file;
        } catch (java.io.IOException e) {
            LOG.warn("could not write the mod report to {}: {}", file, e.toString());
            return null;
        }
    }

    /** What went wrong this launch, for the window and the report file. */
    private final LoadingReport report = new LoadingReport();

    public LoadingReport report() {
        return report;
    }

    private LaunchResult loadMods(OctoRuntime runtime, OctoClassLoader classLoader, PhantomClasses phantoms) {
        TimeCapsule timeCapsule = new TimeCapsule(context);
        installMappings(timeCapsule.runtimeEra());

        Discovery discovery = new ModDiscoverer(context.octoDir().resolve("nested"))
                .discover(context.modDirs());
        List<ModCandidate> candidates = discovery.candidates();
        LOG.info("found {} mod(s) in {} file(s) under {}", candidates.size(), discovery.filesInspected(),
                context.modDirs());
        discovery.describe().forEach(LOG::debug);

        // A jar that says it is a mod and could not be read is never acceptable
        // collateral: it is a mod the player chose, downloaded and installed, so
        // it is named individually rather than counted.
        for (Discovery.Inspection failure : discovery.failures()) {
            record(LoadingReport.Severity.ERROR, failure.file().getFileName().toString(),
                    failure.verdict() == Discovery.Verdict.BROKEN_METADATA
                            ? "its metadata could not be read, so it was not loaded"
                            : "the file could not be opened, so it was not loaded",
                    failure.detail());
        }

        if (candidates.isEmpty() && discovery.filesInspected() > 0) {
            record(LoadingReport.Severity.ERROR, "the mods folder",
                    "none of the " + discovery.filesInspected() + " file(s) here turned out to be a mod",
                    String.join(System.lineSeparator(), discovery.describe()));
        }

        if (candidates.isEmpty()) {
            LOG.warn("no mods were discovered; scanned {} (expected .jar, .zip, or .litemod files). "
                    + "This is unrelated to any later pack.mcmeta/resource-pack warnings",
                    context.modDirs());
        }

        Resolution resolution = new ModResolver(context).resolve(candidates);

        report(resolution);

        for (Resolution.Problem problem : resolution.fatalProblems()) {
            record(LoadingReport.Severity.ERROR, problem.modId(), problem.message(), "");
        }

        if (!candidates.isEmpty() && resolution.order().isEmpty()) {
            record(LoadingReport.Severity.ERROR, "the mods folder",
                    candidates.size() + " mod(s) were found and none passed dependency resolution",
                    resolution.problems().stream().map(Resolution.Problem::toString)
                            .collect(Collectors.joining(System.lineSeparator())));
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

        // The libraries the mods brought with them. Before this they were
        // unpacked, found to contain no mod, and thrown away — which is why
        // Create loaded and then died on the Registrate it ships itself.
        List<Path> libraries = discovery.libraries();

        if (!libraries.isEmpty()) {
            LOG.info("{} bundled librarie(s) came with the mods and are on the class path", libraries.size());
            libraries.forEach(classLoader::addLibrary);
        }

        // All of these rewrite the game itself on behalf of the mods, so they are
        // installed before a single class is loaded and apply to every jar —
        // including the mixin configuration plugins mixin loads during its own
        // bootstrap, which is where a mod first calls into the loader's API.
        installApiGapCover(classLoader);
        installVersionAdapters(phantoms, classLoader);
        installAccessRules(mods, classLoader);

        // Planned before mixin starts: mixin reads its targets through the class
        // loader, and a mixin config plugin is the first thing in a launch to
        // touch a class that may not be there.
        if (context.compat().stubMissingApi()) {
            planPhantoms(mods, phantoms, classLoader, timeCapsule);
        }

        installMixins(mods, classLoader);

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(classLoader);

        try {
            // Before any mod runs, not after: a mod's initialiser is entitled to
            // register a block or read a tag, and both need the game's registries
            // to exist.
            reportBootstrap(GameBootstrap.run(classLoader));

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

        // Not a failure, but the most useful thing in the log for anyone working
        // on Octo: the exact list of API this mod folder wanted and did not find.
        if (apiGaps != null && !apiGaps.gaps().isEmpty()) {
            LOG.warn("{} loader API member(s) are not implemented and returned defaults:", apiGaps.gaps().size());
            apiGaps.gaps().forEach(gap -> LOG.warn("  {}", gap));
            record(LoadingReport.Severity.WARNING, "Octo",
                    apiGaps.gaps().size() + " loader API member(s) are not implemented yet and returned defaults",
                    String.join(System.lineSeparator(), apiGaps.gaps()));
        }

        // Not a failure either, and worth saying out loud: a mod that is running
        // against a differently-named class is running, but it is not running
        // against what its author tested.
        if (substitutions != null && !substitutions.substitutions().isEmpty()) {
            LOG.info("{} class(es) this Minecraft does not have were answered by an equivalent:",
                    substitutions.substitutions().size());
            substitutions.substitutions().forEach(entry -> LOG.info("  {}", entry));
        }

        if (mixinTargets != null && !mixinTargets.adjustments().isEmpty()) {
            LOG.info("{} mixin target(s) did not match this version and were re-aimed or skipped:",
                    mixinTargets.adjustments().size());
            mixinTargets.adjustments().forEach(entry -> LOG.info("  {}", entry));
        }

        for (LoadedMod mod : failed) {
            record(LoadingReport.Severity.ERROR, mod.id(), "it failed while starting up, so it is not running",
                    Failures.describe(mod.failure()));
        }

        return new LaunchResult(runtime, resolution, classLoader, loaded, failed);
    }

    /**
     * Says so when the game's own start-up did not finish.
     *
     * <p>Worth a line of its own in the report because of what it looks like
     * when it is not reported: the launch carries on, the registries are short of
     * whatever the failed step would have added, and the game dies later with a
     * null default entry that names nothing and nobody. Here the cause is on
     * screen before the game gets that far.
     */
    private void reportBootstrap(GameBootstrap.Outcome outcome) {
        // A bootstrap that returned without filling the registries is the failure
        // that reaches the player as a null default entry minutes later, naming
        // nothing. Named here, while there is still something to name.
        if (!outcome.registryProblems().isEmpty()) {
            record(LoadingReport.Severity.ERROR, "Minecraft",
                    outcome.registryProblems().size()
                            + " of the game's registries did not finish building, so the game will very "
                            + "likely stop while it starts",
                    String.join(System.lineSeparator(), outcome.registryProblems())
                            + System.lineSeparator()
                            + "A mod ran during the game's own start-up and stopped part of it. Start once "
                            + "with -Docto.safeMode=true to confirm it is a mod, and once with "
                            + "-Docto.noEarlyBootstrap=true to rule out Octo's own early start-up."
                            + System.lineSeparator()
                            + "The full launch log is in " + logFile() + ".");
        }

        if (outcome.failure() == null) {
            return;
        }

        String next = outcome.retried()
                ? "Minecraft will try the same start-up again itself, so it may still start."
                : "Minecraft skips its own attempt once this one has been made, so parts of the game may "
                        + "be missing.";

        record(LoadingReport.Severity.ERROR, "Minecraft",
                "the game's own start-up did not finish before the mods ran",
                Failures.describe(outcome.failure()) + System.lineSeparator() + next + System.lineSeparator()
                        + "The full stack trace is in " + logFile() + ".");
    }

    /**
     * Notes something that went wrong, without stopping.
     *
     * <p>Every path that could end a launch comes through here, so "what does
     * Octo do about a mod that will not load" is answered in one place: it
     * writes it down and keeps going, and the player is shown the list when the
     * game starts. Under {@code -Docto.strict=true} the first error is thrown
     * instead, which is what a build or a server operator wants.
     */
    private void record(LoadingReport.Severity severity, String source, String summary, String detail) {
        report.add(severity, source, summary, detail);

        if (severity == LoadingReport.Severity.ERROR) {
            LOG.error("{}: {}", source, summary);
        } else {
            LOG.warn("{}: {}", source, summary);
        }

        if (severity == LoadingReport.Severity.ERROR && context.compat().strict()) {
            throw new ModLoadingException(source + ": " + summary,
                    detail == null || detail.isBlank() ? List.of() : detail.lines().toList());
        }
    }

    private Path logFile() {
        return context.octoDir().resolve("logs").resolve("octo-loader.log");
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

        // From here on the game owns the process, and the process is how the
        // report is shown. If Minecraft dies it takes the JVM — and the window —
        // with it, so the watcher has to be in place before that can happen.
        if (context.side() == Side.CLIENT && !Boolean.getBoolean("octo.noReportWindow")) {
            GameWatch.arm(report, context.gameDir(), logFile());
        }

        try {
            Class<?> type = Class.forName(mainClass, false, result.classLoader());
            Method main = type.getMethod("main", String[].class);
            LOG.info("handing over to {}", mainClass);
            main.invoke(null, (Object) context.launchArguments().toArray(new String[0]));
        } catch (Throwable e) {
            // Minecraft normally catches its own crashes and exits before this
            // ever runs; when it does not, this is the only place the failure
            // exists. Either way it belongs in the window, not only in a
            // launcher dialog reading "Exit code: -1".
            GameWatch.crashed(e);
            throw e;
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
    /**
     * Makes a call into a corner of the four APIs that Octo has not implemented
     * return a default instead of killing the launch.
     *
     * <p>Octo implements the mod-facing half of four loaders, and "the mod-facing
     * half" has no edge — every large mod reaches somewhere nobody anticipated.
     * Before this, one such call was a {@code NoSuchMethodError} at class-load
     * time, which is fatal wherever it happens and happens most often inside a
     * mixin plugin, before any mod has run.
     */
    private void installApiGapCover(OctoClassLoader classLoader) {
        apiGaps = new MissingMemberTransformer(getClass().getClassLoader());
        classLoader.addGlobalTransformer(apiGaps);
    }

    /** What the mods asked Octo for and did not get, collected over the launch. */
    private MissingMemberTransformer apiGaps;

    /**
     * Teaches the launch what this Minecraft has instead of what the mods expect.
     *
     * <p>Three things, all versions of one problem — a mod naming something the
     * running game spells differently or does not have at all:
     *
     * <ul>
     *   <li>a class that survives under another name is redirected to it;</li>
     *   <li>a class that is gone outright may be stood in for, if the table says
     *       so;</li>
     *   <li>a mixin aimed at a member that moved is re-aimed, and one aimed at a
     *       member that is genuinely gone is made optional instead of fatal.</li>
     * </ul>
     *
     * <p>None of it happens until the original has actually been looked for and
     * not found, which is what lets the same rules be right on every Minecraft
     * version: on one that has everything, all three are inert.
     */
    private void installVersionAdapters(PhantomClasses phantoms, OctoClassLoader classLoader) {
        equivalents = Equivalents.load(context.octoDir().resolve("api"));

        if (!equivalents.isEmpty()) {
            LOG.debug("{} version-equivalence entrie(s) available", equivalents.size());
            phantoms.useEquivalents(equivalents);
            substitutions = new MissingClassTransformer(equivalents);
            classLoader.addGlobalTransformer(substitutions);
        }

        // Installed whether or not the table has anything in it: most of what
        // re-aims a mixin is read off the target class, not out of a table.
        mixinTargets = new MixinTargets(equivalents, classLoader::rawClassBytes);
        classLoader.addGlobalTransformer(mixinTargets);
    }

    /** What this Minecraft has instead of what the mods expect; read once per launch. */
    private Equivalents equivalents = Equivalents.EMPTY;

    /** Which absent classes were answered by a different one, collected over the launch. */
    private MissingClassTransformer substitutions;

    /** Which mixins had to be re-aimed or given up on, collected over the launch. */
    private MixinTargets mixinTargets;

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
        try {
            classLoader.installWeaver(MixinSupport.bootstrap(classLoader, mods, context.side()));
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            OctoLog.detail(e);

            // Without mixin, Sodium, Create, Iris and the whole Fabric API load
            // and then fail at their first call into something a mixin was meant
            // to have added, so this is reported as the cause rather than left
            // for ten unrelated-looking mod failures to imply.
            record(LoadingReport.Severity.ERROR, "Octo",
                    "mixin could not start, so no mod's mixins were applied",
                    Failures.describe(e) + System.lineSeparator()
                            + "The full stack trace is in " + logFile() + ".");
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

    /**
     * Everything the loader's class loader owns: the game, and the libraries it
     * came with.
     *
     * <p>The libraries matter as much as the game does. A class the loader does
     * not own is loaded by the class loader that started Octo, which means no
     * mod's mixin can touch it and no interface a mod adds to it exists on the
     * copy the game actually uses. Fabric API's dimension module adds an
     * interface to DataFixerUpper's {@code TaggedChoice} and casts to it from a
     * Minecraft class; with the library outside and Minecraft inside, that cast
     * is between two unrelated classes with the same name, and the game dies in
     * its own static initialiser before drawing anything.
     */
    private List<URL> gameUrls() {
        List<URL> urls = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();

        for (Path jar : context.gameJars()) {
            if (!Files.exists(jar)) {
                LOG.warn("game jar {} does not exist", jar);
                continue;
            }

            add(urls, seen, jar);
        }

        for (Path jar : context.libraryJars()) {
            if (Files.exists(jar)) {
                add(urls, seen, jar);
            }
        }

        LOG.debug("the loader owns {} class path entrie(s)", urls.size());
        return urls;
    }

    private void add(List<URL> urls, Set<Path> seen, Path jar) {
        Path normalised = jar.toAbsolutePath().normalize();

        if (!seen.add(normalised)) {
            return;
        }

        try {
            urls.add(normalised.toUri().toURL());
        } catch (MalformedURLException e) {
            LOG.warn("could not use {}: {}", normalised, e.toString());
        }
    }

    /**
     * Reads every mod's classes, so the loader knows in advance which of the
     * classes they reference are not there and what shape a stand-in has to be.
     *
     * <p>Knowing the shape is the whole value of doing this in advance. A
     * stand-in invented at the moment a class fails to load can only be an empty
     * interface, because nothing at that point knows what the mod wanted from it;
     * one planned from the mod's own bytecode has the constructors the mod calls
     * and the methods it invokes, with the right descriptors. That is the
     * difference between {@code new ItemStackHandler(9)} working and failing.
     *
     * <p>What each mod is answered for differs. A mod older than the runtime is
     * answered for everything, because the game moved out from under it and that
     * is what the time capsule is for. A mod built for this version is answered
     * only where Octo is responsible: the four loaders' APIs, and the classes the
     * compatibility table names. Standing in for anything else there would turn a
     * mod with a genuine problem into a mod that loads and silently does nothing.
     */
    private void planPhantoms(List<LoadedMod> mods, PhantomClasses phantoms, OctoClassLoader classLoader,
            TimeCapsule timeCapsule) {
        Equivalents table = equivalents;

        // A current mod's classes are only worth parsing if they mention one of
        // the packages Octo answers for. That is a byte scan rather than an ASM
        // parse, which is the difference between this being free on a folder of
        // two hundred mods and it being a second of startup nobody asked for.
        ByteScan worthReading = new ByteScan(answerablePrefixes(table));

        for (LoadedMod mod : mods) {
            boolean fromAnotherEra = mod.era().isKnown() && mod.era().isOlderThan(timeCapsule.runtimeEra());
            java.util.function.Predicate<String> answerable = fromAnotherEra
                    ? name -> true
                    : name -> isOctosToAnswerFor(name, table);
            TransformPipeline pipeline = fromAnotherEra ? timeCapsule.pipelineFor(mod) : TransformPipeline.empty();

            try (ModSource source = ModSource.open(mod.effectivePath())) {
                for (String entry : source.entries()) {
                    if (!entry.endsWith(".class")) {
                        continue;
                    }

                    byte[] bytes = source.read(entry).orElse(null);

                    if (bytes == null || (!fromAnotherEra && !worthReading.matches(bytes))) {
                        continue;
                    }

                    // Look at the class as it will be after translation, not as
                    // it is on disk: remapping resolves most missing references.
                    byte[] translated = pipeline.apply(entry.substring(0, entry.length() - 6), bytes,
                            studios.milkdromeda.octo.transform.TransformContext.of(null, classLoader::classExists));
                    phantoms.observe(translated, classLoader::classExists, answerable);
                }
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.debug("could not scan {} for missing references: {}", mod.id(), e.toString());
            }
        }

        if (!phantoms.specs().isEmpty()) {
            LOG.info("{} referenced class(es) are not in this runtime; stand-ins are ready",
                    phantoms.specs().size());
            phantoms.specs().values().forEach(spec -> LOG.debug("  stand-in ready for {}", spec));
        }
    }

    /**
     * Whether a class a current mod is missing is Octo's to manufacture.
     *
     * <p>The loader packages are, unconditionally: Octo implements the mod-facing
     * half of four loaders and every gap in that is Octo's own. Anything else has
     * to be named in the compatibility table, which is where the judgement about
     * a particular class belongs.
     */
    private static boolean isOctosToAnswerFor(String internalName, Equivalents table) {
        for (String prefix : OctoClassLoader.STAND_IN_PACKAGES) {
            if (internalName.startsWith(prefix)) {
                return true;
            }
        }

        Equivalents.ClassEntry entry = table.classEntry(internalName);
        return entry != null && entry.stub() != Equivalents.Stub.NONE;
    }

    /** The names whose presence in a class file makes it worth parsing at all. */
    private static List<String> answerablePrefixes(Equivalents table) {
        List<String> out = new ArrayList<>(OctoClassLoader.STAND_IN_PACKAGES);
        out.addAll(table.mentionedClasses());
        return out;
    }

    private void construct(List<LoadedMod> mods, OctoClassLoader classLoader) {
        for (LoadedMod mod : mods) {
            LoaderBridge bridge = bridges.forFormat(mod.format());

            if (bridge == null) {
                LOG.warn("{}: no bridge handles {} mods", mod.id(), mod.format());
                continue;
            }

            ForgeBuses.setActive(mod.id());

            try {
                bridge.construct(mod, classLoader);
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.error("{}: construction failed, skipping this mod: {}", mod.id(), Failures.describe(e));
                OctoLog.detail(e);
                mod.fail(e);
            } finally {
                ForgeBuses.setActive(null);
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

            // Which mod is running right now, for the whole of its turn rather
            // than only while it is being constructed. A Forge mod registering a
            // config from inside FMLCommonSetupEvent — which is where the
            // documentation tells it to — was until now registering it against
            // "minecraft", because the marker had already been cleared.
            ForgeBuses.setActive(mod.id());

            try {
                bridge.dispatch(phase, mod);
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.error("{}: {} failed, skipping this mod: {}", mod.id(), phase, Failures.describe(e));
                OctoLog.detail(e);
                mod.fail(e);
            } finally {
                ForgeBuses.setActive(null);
            }
        }
    }
}
