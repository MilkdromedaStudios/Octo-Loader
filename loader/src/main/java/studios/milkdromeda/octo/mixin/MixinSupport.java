package studios.milkdromeda.octo.mixin;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Manifest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.util.asm.ASM;

import studios.milkdromeda.octo.launch.OctoClassLoader;
import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.transform.TransformContext;
import studios.milkdromeda.octo.transform.Transformer;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Starts mixin and hands the class loader something that applies it.
 *
 * <p>Mixin is how essentially every current mod changes the game: Sodium,
 * Create, Iris and the whole Fabric API are mixins on top of vanilla classes.
 * Without it those mods load, run their initialiser, and then fail the moment
 * they call something a mixin was supposed to have added — which is exactly
 * what {@code SodiumClientMod.onInitialization} did when it asked a mixin
 * accessor for the debug screen's entries and got the {@code AssertionError}
 * that every unapplied accessor throws.
 *
 * <p>Both spellings of mixin declaration are read here — Fabric's {@code mixins}
 * array, Forge's {@code [[mixins]]} table and the {@code MixinConfigs} manifest
 * attribute — into one environment, so a Fabric mod and a Forge mod can mix into
 * the same class.
 */
public final class MixinSupport {
    private static final OctoLog LOG = OctoLog.of(MixinSupport.class);

    /** Where Forge mods list their configs when they do not use {@code mods.toml}. */
    private static final String MANIFEST_ATTRIBUTE = "MixinConfigs";

    /**
     * Mixin's state is per-JVM, not per-launch, and registering a config twice
     * applies it twice. A game is launched once per JVM, so this only matters to
     * the tests and to {@code octo scan}, but getting it wrong there is what
     * makes a suite pass alone and fail in company.
     */
    private static final Set<String> REGISTERED = new LinkedHashSet<>();
    private static boolean booted;

    private MixinSupport() {
    }

    /** Octo's own configuration, which hooks the game's startup. */
    private static final String OWN_CONFIG = "octo.mixins.json";

    /**
     * Boots mixin over the mods that carry configs.
     *
     * @return the transformer to install on the class loader
     * @throws IllegalStateException when mixin could not be started, which the
     *         caller turns into a refusal to launch. Returning {@code null} and
     *         logging, as this used to, produced a game where every mixin-based
     *         mod was present and quietly inert.
     */

    public static synchronized Transformer bootstrap(OctoClassLoader classLoader, List<LoadedMod> mods, Side side) {
        raiseCompatibilityCeiling();
        List<Config> configs = new ArrayList<>(discover(mods));

        // Always registered, even with no mixin-using mods present: this is how
        // the loader learns that the game has started, and a Fabric mod's client
        // initialiser is meant to run at that moment rather than before it.
        configs.add(new Config("octo", OWN_CONFIG));

        OctoMixinService.attach(classLoader);

        try {
            MixinBootstrap.init();
            MixinEnvironment.getDefaultEnvironment().setSide(side == Side.CLIENT
                    ? MixinEnvironment.Side.CLIENT
                    : MixinEnvironment.Side.SERVER);

            for (Config config : configs) {
                if (!REGISTERED.add(config.path())) {
                    continue;
                }

                Mixins.addConfiguration(config.path());
                LOG.debug("{}: registered mixin config {}", config.modId(), config.path());
            }

            initialiseMixinExtras();

            if (!booted) {
                finishBootstrap();
                booted = true;
            }

            IMixinTransformer transformer = OctoMixinService.transformer();

            if (transformer == null) {
                throw new IllegalStateException("mixin started but produced no transformer");
            }

            LOG.info("mixin {} ready with {} config(s) from {} mod(s), up to {}", MixinBootstrap.VERSION,
                    configs.size(), configs.stream().map(Config::modId).distinct().count(),
                    MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED);
            return new MixinTransformer(transformer);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            throw new IllegalStateException("mixin could not start: " + Failures.describe(e), e);
        }
    }

    /**
     * Test hook: detaches the class loader mixin was pointed at.
     *
     * <p>Mixin cannot actually be unloaded — its environment is static and its
     * class metadata is cached for the life of the JVM — so a test that needs a
     * genuinely clean mixin needs a fresh JVM. Dropping the class loader at least
     * stops a finished launch from being asked about classes.
     */
    public static void reset() {
        OctoMixinService.attach(null);
    }

    private record Config(String modId, String path) {
    }

    private static List<Config> discover(List<LoadedMod> mods) {
        List<Config> out = new ArrayList<>();

        for (LoadedMod mod : mods) {
            Set<String> declared = new LinkedHashSet<>(mod.metadata().mixinConfigs());

            try (ModSource source = ModSource.open(mod.effectivePath())) {
                declared.addAll(fromManifest(source));

                for (String path : declared) {
                    byte[] bytes = source.read(path).orElse(null);

                    if (bytes == null) {
                        // Mods routinely declare a config for a side or a game
                        // version they did not ship; registering it anyway makes
                        // mixin throw during startup instead.
                        LOG.debug("{}: declares mixin config {} which is not in the jar", mod.id(), path);
                        continue;
                    }

                    if (!isSupported(bytes, mod.id(), path)) {
                        continue;
                    }

                    out.add(new Config(mod.id(), path));
                }
            } catch (IOException | RuntimeException e) {
                // The jar was readable minutes ago, during discovery. If it is not
                // readable now, this mod's mixins are silently absent, and the mod
                // will fail later in a way that names the wrong culprit.
                throw new IllegalStateException(mod.id() + ": could not read its mixin configs", e);
            }
        }

        return out;
    }

    /**
     * Raises the highest Java level mixin will admit to supporting.
     *
     * <p>Mixin ships with this pinned at {@code JAVA_13} and expects its host to
     * work out what the running JVM and the bundled ASM can really handle —
     * every other loader does this, which is why nobody sees it. Left alone, a
     * mixin config declaring {@code "compatibilityLevel": "JAVA_17"} — which is
     * every mod written in the last four years — is refused outright, and the
     * refusal takes the whole mixin environment with it.
     */
    private static void raiseCompatibilityCeiling() {
        int ceiling = Math.min(Runtime.version().feature() + JAVA_CLASS_VERSION_OFFSET,
                ASM.getMaxSupportedClassVersionMajor());
        MixinEnvironment.CompatibilityLevel best = MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED;

        for (MixinEnvironment.CompatibilityLevel level : MixinEnvironment.CompatibilityLevel.values()) {
            if (level.getClassMajorVersion() <= ceiling && level.isAtLeast(best)) {
                best = level;
            }
        }

        if (best != MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED) {
            LOG.debug("raising the mixin compatibility ceiling from {} to {} (Java {}, {})",
                    MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED, best, Runtime.version().feature(),
                    ASM.getVersionString());
            MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED = best;
        }
    }

    /** Class file major version 52 is Java 8, 61 is Java 17, and so on. */
    private static final int JAVA_CLASS_VERSION_OFFSET = 44;

    /**
     * Whether this runtime can offer the language level the config asks for.
     *
     * <p>Mixin refuses a whole configuration whose {@code compatibilityLevel} is
     * higher than the running JVM and ASM can support, and that refusal comes out
     * of the phase transition at the end of bootstrap — where it takes every
     * other mod's mixins down with it. Checking here costs one JSON parse and
     * turns "no mod in the game has working mixins" into "this one mod needs a
     * newer Java".
     */
    private static boolean isSupported(byte[] config, String modId, String path) {
        String declared;

        try {
            JsonElement root = JsonParser.parseString(new String(config, StandardCharsets.UTF_8));

            if (!root.isJsonObject()) {
                return true;
            }

            JsonElement level = root.getAsJsonObject().get("compatibilityLevel");
            declared = level != null && level.isJsonPrimitive() ? level.getAsString() : null;
        } catch (RuntimeException e) {
            // Malformed configs are mixin's to complain about, in its own words.
            return true;
        }

        if (declared == null) {
            return true;
        }

        try {
            MixinEnvironment.CompatibilityLevel wanted = MixinEnvironment.CompatibilityLevel.valueOf(declared);

            if (MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED.isAtLeast(wanted)) {
                return true;
            }

            LOG.warn("{}: {} needs mixin compatibility {} but this runtime supports at most {}; "
                    + "its mixins are skipped so that other mods keep theirs",
                    modId, path, wanted, MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED);
            return false;
        } catch (IllegalArgumentException e) {
            // A level from a newer mixin than this one: let mixin decide.
            return true;
        }
    }

    private static List<String> fromManifest(ModSource source) {
        byte[] bytes = source.read("META-INF/MANIFEST.MF").orElse(null);

        if (bytes == null) {
            return List.of();
        }

        try {
            Manifest manifest = new Manifest(new ByteArrayInputStream(bytes));
            String declared = manifest.getMainAttributes().getValue(MANIFEST_ATTRIBUTE);

            if (declared == null || declared.isBlank()) {
                return List.of();
            }

            List<String> out = new ArrayList<>();

            for (String name : declared.split("[\\s,]+")) {
                if (!name.isBlank()) {
                    out.add(name.trim());
                }
            }

            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Runs the mixin phases the game would normally advance through.
     *
     * <p>Mixin will not apply anything while it is still in {@code PREINIT}, and
     * the method that moves it on is deliberately not public, because under every
     * other loader the launch wrapper owns that decision. Octo is the launch
     * wrapper here, so it makes the call.
     */
    private static void finishBootstrap() throws ReflectiveOperationException {
        Method gotoPhase = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
        gotoPhase.setAccessible(true);
        gotoPhase.invoke(null, MixinEnvironment.Phase.INIT);
        gotoPhase.invoke(null, MixinEnvironment.Phase.DEFAULT);
    }

    /**
     * Starts MixinExtras if it is on the class path.
     *
     * <p>Current mods are written against {@code @WrapOperation} and friends and
     * expect the loader to have initialised the library, exactly as Fabric Loader
     * does. Mods that bundle their own relocated copy are unaffected.
     */
    private static void initialiseMixinExtras() {
        try {
            Class<?> bootstrap = Class.forName("com.llamalad7.mixinextras.MixinExtrasBootstrap", true,
                    MixinSupport.class.getClassLoader());
            bootstrap.getMethod("init").invoke(null);
            LOG.debug("MixinExtras initialised");
        } catch (ClassNotFoundException e) {
            LOG.debug("MixinExtras is not present; mods relying on it will need their own copy");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("MixinExtras could not start: {}", e.toString());
        }
    }

    /** Runs the mixins that target a class, as that class is loaded. */
    private static final class MixinTransformer implements Transformer {
        private final IMixinTransformer transformer;

        MixinTransformer(IMixinTransformer transformer) {
            this.transformer = transformer;
        }

        @Override
        public String name() {
            return "mixin";
        }

        @Override
        public byte[] transform(String className, byte[] bytes, TransformContext context) {
            String binaryName = className.replace('/', '.');
            byte[] result = transformer.transformClassBytes(binaryName, binaryName, bytes);

            if (result != null && result != bytes && result.length != bytes.length) {
                context.note("mixins applied to " + binaryName);
            }

            return result == null ? bytes : result;
        }
    }
}
