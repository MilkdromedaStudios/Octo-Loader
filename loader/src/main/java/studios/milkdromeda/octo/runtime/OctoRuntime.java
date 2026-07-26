package studios.milkdromeda.octo.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import studios.milkdromeda.octo.launch.LaunchContext;
import studios.milkdromeda.octo.mod.Side;

/**
 * The single source of truth every ecosystem's API is wired to.
 *
 * <p>{@code FabricLoader.getInstance()}, {@code QuiltLoader.isModLoaded()} and
 * {@code ModList.get()} all end up here, which is what lets mods from different
 * ecosystems see each other. A Forge mod asking whether "sodium" is loaded gets
 * a true answer about a Fabric mod.
 */
public final class OctoRuntime {
    public static final String VERSION = "1.0.0";

    private static volatile OctoRuntime instance;

    private final LaunchContext context;
    private final List<LoadedMod> mods = new ArrayList<>();
    private final Map<String, LoadedMod> modsById = new LinkedHashMap<>();
    private final Map<String, List<Entrypoint>> entrypoints = new LinkedHashMap<>();
    private final Map<String, Object> objectShare = new ConcurrentHashMap<>();
    private final Map<String, List<BiConsumer<String, Object>>> shareWaiters = new ConcurrentHashMap<>();
    private volatile Lifecycle phase = Lifecycle.PRE_LAUNCH;
    private volatile Object gameInstance;

    private OctoRuntime(LaunchContext context) {
        this.context = context;
    }

    public static OctoRuntime initialise(LaunchContext context) {
        OctoRuntime created = new OctoRuntime(context);
        instance = created;
        return created;
    }

    /** @throws IllegalStateException when a mod reaches for the loader before it exists */
    public static OctoRuntime get() {
        OctoRuntime current = instance;

        if (current == null) {
            throw new IllegalStateException("Octo Loader accessed before it started");
        }

        return current;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    /** Test hook: drops the singleton so a second launch can be set up in one JVM. */
    public static void reset() {
        instance = null;
    }

    public LaunchContext context() {
        return context;
    }

    public Path gameDir() {
        return context.gameDir();
    }

    public Path configDir() {
        return context.configDir();
    }

    public Side side() {
        return context.side();
    }

    public String minecraftVersion() {
        return context.minecraftVersion();
    }

    public boolean isDevelopment() {
        return context.isDevelopment();
    }

    public Lifecycle phase() {
        return phase;
    }

    public void phase(Lifecycle value) {
        this.phase = value;
    }

    public Object gameInstance() {
        return gameInstance;
    }

    public void gameInstance(Object value) {
        this.gameInstance = value;
    }

    public void register(LoadedMod mod) {
        mods.add(mod);
        modsById.put(mod.id(), mod);
    }

    /** An entrypoint object together with the mod that supplied it. */
    public record Entrypoint(String key, LoadedMod mod, Object instance) {
    }

    /**
     * Records a constructed entrypoint under a key.
     *
     * <p>Keys are the ones mods already use — {@code main}, {@code client},
     * {@code preLaunch}, and whatever custom key a mod invented — so
     * {@code FabricLoader.getEntrypoints("mymod:api", ...)} keeps working, and
     * now also finds entrypoints registered by mods from other ecosystems.
     */
    public void registerEntrypoint(String key, LoadedMod mod, Object instance) {
        entrypoints.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Entrypoint(key, mod, instance));
        mod.instances().add(instance);
    }

    public List<Entrypoint> entrypoints(String key) {
        return List.copyOf(entrypoints.getOrDefault(key, List.of()));
    }

    public List<LoadedMod> mods() {
        return List.copyOf(mods);
    }

    public Collection<LoadedMod> loadedMods() {
        List<LoadedMod> out = new ArrayList<>(mods.size());

        for (LoadedMod mod : mods) {
            if (!mod.isFailed()) {
                out.add(mod);
            }
        }

        return out;
    }

    public Optional<LoadedMod> mod(String id) {
        return Optional.ofNullable(modsById.get(id));
    }

    public boolean isModLoaded(String id) {
        LoadedMod mod = modsById.get(id);
        return mod != null && !mod.isFailed();
    }

    // ------------------------------------------------------------ object share

    public Object shareGet(String key) {
        return objectShare.get(key);
    }

    public Object sharePut(String key, Object value) {
        Object previous = objectShare.put(key, value);
        notifyWaiters(key, value);
        return previous;
    }

    public Object sharePutIfAbsent(String key, Object value) {
        Object previous = objectShare.putIfAbsent(key, value);

        if (previous == null) {
            notifyWaiters(key, value);
        }

        return previous;
    }

    public Object shareRemove(String key) {
        return objectShare.remove(key);
    }

    public void shareWhenAvailable(String key, BiConsumer<String, Object> consumer) {
        Object existing = objectShare.get(key);

        if (existing != null) {
            consumer.accept(key, existing);
            return;
        }

        shareWaiters.computeIfAbsent(key, ignored -> new ArrayList<>()).add(consumer);
    }

    private void notifyWaiters(String key, Object value) {
        List<BiConsumer<String, Object>> waiters = shareWaiters.remove(key);

        if (waiters != null) {
            waiters.forEach(waiter -> waiter.accept(key, value));
        }
    }
}
