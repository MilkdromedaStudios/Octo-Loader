package studios.milkdromeda.octo.bridge.forge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * The game events NeoForge's patched game fires, fired from the unpatched one.
 *
 * <p>NeoForge mods do their running work on its game bus: Create advances
 * every animation from the client tick, moves its contraptions and runs its
 * train schedules from the level tick, registers its commands from {@code
 * RegisterCommandsEvent} and tracks its worlds from {@code LevelEvent}. Each
 * of those is fired by a line NeoForge patches into the game, and this game
 * is not patched, so none of it happened: parts drew but never turned.
 * {@code GameEventHooks} puts a call to here where each patch was; this
 * makes NeoForge's own event and posts it on {@code NeoForge.EVENT_BUS}.
 *
 * <p>Every call is cheap when nothing listens, and a launch without
 * NeoForge's API jar finds no event classes and does nothing at all.
 */
public final class NeoGameEvents {
    private static final OctoLog LOG = OctoLog.of(NeoGameEvents.class);
    private static final String EVENT = "net.neoforged.neoforge.event.";

    private static final Map<String, Optional<Constructor<?>>> CONSTRUCTORS = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    /** Servers {@code ServerStartedEvent} has been posted for. */
    private static final Set<Object> STARTED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static volatile Object bus;

    private NeoGameEvents() {
    }

    // ------------------------------------------------------------------ ticks

    public static void clientTick(Object minecraft, boolean pre) {
        post(minecraft, "net.neoforged.neoforge.client.event.ClientTickEvent$" + (pre ? "Pre" : "Post"));
    }

    public static void serverTick(Object server, Object hasTime, boolean pre) {
        if (pre) {
            boolean first;

            synchronized (STARTED) {
                first = STARTED.add(server);
            }

            // Started comes once, as the server begins ticking: after
            // initServer, where NeoForge posts it.
            if (first) {
                post(server, EVENT + "server.ServerStartedEvent", server);
            }
        }

        post(server, EVENT + "tick.ServerTickEvent$" + (pre ? "Pre" : "Post"), hasTime, server);
    }

    public static void levelTick(Object level, Object hasTime, boolean pre) {
        post(level, EVENT + "tick.LevelTickEvent$" + (pre ? "Pre" : "Post"), hasTime, level);
    }

    public static void playerTick(Object player, boolean pre) {
        post(player, EVENT + "tick.PlayerTickEvent$" + (pre ? "Pre" : "Post"), player);
    }

    // -------------------------------------------------------------- lifecycle

    public static void serverAboutToStart(Object server) {
        currentServer(server, server);
        post(server, EVENT + "server.ServerAboutToStartEvent", server);
    }

    public static void serverStarting(Object server) {
        post(server, EVENT + "server.ServerStartingEvent", server);
    }

    public static void serverStopping(Object server) {
        post(server, EVENT + "server.ServerStoppingEvent", server);
    }

    public static void serverStopped(Object server) {
        post(server, EVENT + "server.ServerStoppedEvent", server);
        currentServer(server, null);

        synchronized (STARTED) {
            STARTED.remove(server);
        }
    }

    public static void registerCommands(Object commands, Object selection, Object context) {
        try {
            Object dispatcher = commands.getClass().getMethod("getDispatcher").invoke(commands);
            post(commands, EVENT + "RegisterCommandsEvent", dispatcher, selection, context);
        } catch (ReflectiveOperationException | RuntimeException e) {
            report("RegisterCommandsEvent", e);
        }
    }

    public static void levelLoad(Object level) {
        post(level, EVENT + "level.LevelEvent$Load", level);
    }

    public static void levelUnload(Object level) {
        post(level, EVENT + "level.LevelEvent$Unload", level);
    }

    /**
     * A chunk about to drop its block entities because it is unloading, not
     * because they were broken: NeoForge's game tells each one so first.
     * Create's block entities clean up after themselves on removal unless
     * told, and a basin doing that as its chunk unloaded asked for the
     * chunk beside it — the server waited on itself and never saved.
     */
    public static void chunkUnloading(Object chunk) {
        Map<?, ?> blockEntities = null;

        // Declared on ChunkAccess, which LevelChunk extends.
        for (Class<?> type = chunk.getClass(); type != null && blockEntities == null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("blockEntities");
                field.setAccessible(true);
                blockEntities = (Map<?, ?>) field.get(chunk);
            } catch (NoSuchFieldException e) {
                // Higher up.
            } catch (ReflectiveOperationException | RuntimeException e) {
                break;
            }
        }

        if (blockEntities == null) {
            if (REPORTED.add("chunkUnloading")) {
                LOG.warn("{} has no block entities to tell its chunk is unloading", chunk.getClass().getName());
            }

            return;
        }

        for (Object blockEntity : List.copyOf(blockEntities.values())) {
            try {
                Method unloaded = UNLOADED.computeIfAbsent(blockEntity.getClass(), NeoGameEvents::unloadedMethod)
                        .orElse(null);

                if (unloaded != null) {
                    unloaded.invoke(blockEntity);
                }
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                Throwable cause = Failures.unwrap(e);

                if (REPORTED.add("onChunkUnloaded:" + blockEntity.getClass().getName())) {
                    LOG.warn("{} could not be told its chunk is unloading: {}", blockEntity.getClass().getName(),
                            Failures.describe(cause));
                }
            }
        }
    }

    /** A block or block entity about to change at a position in a chunk. */
    public static void blockChanged(Object chunk, Object posOrBlockEntity) {
        CapabilityListeners.changed(chunk, posOrBlockEntity);
    }

    private static final Map<Class<?>, Optional<Method>> UNLOADED = new ConcurrentHashMap<>();

    private static Optional<Method> unloadedMethod(Class<?> type) {
        try {
            Method method = type.getMethod("onChunkUnloaded");
            method.setAccessible(true);
            return Optional.of(method);
        } catch (NoSuchMethodException | RuntimeException e) {
            return Optional.empty();
        }
    }

    public static void playerLoggedIn(Object player) {
        post(player, EVENT + "entity.player.PlayerEvent$PlayerLoggedInEvent", player);
    }

    public static void playerLoggedOut(Object player) {
        post(player, EVENT + "entity.player.PlayerEvent$PlayerLoggedOutEvent", player);
    }

    // ---------------------------------------------------------------- posting

    /**
     * Makes the event named and posts it on NeoForge's game bus.
     *
     * @param anchor any game object, for the class loader the event lives in
     */
    static void post(Object anchor, String eventName, Object... arguments) {
        if (anchor == null) {
            return;
        }

        ClassLoader loader = anchor.getClass().getClassLoader();
        Optional<Constructor<?>> constructor = CONSTRUCTORS.computeIfAbsent(eventName,
                name -> constructorFor(loader, name, arguments));

        if (constructor.isEmpty()) {
            return;
        }

        Object target = bus(loader);

        if (!(target instanceof OctoNeoEventBus neoBus) || neoBus.listenerCount() == 0) {
            return;
        }

        try {
            neoBus.postAny(constructor.get().newInstance(arguments));
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            report(eventName, Failures.unwrap(e));
        }
    }

    private static Optional<Constructor<?>> constructorFor(ClassLoader loader, String name, Object[] arguments) {
        // Asked of the jar first: the class loader stands in for missing
        // loader classes, and a stand-in event reaches nobody.
        if (loader == null || loader.getResource(name.replace('.', '/') + ".class") == null) {
            return Optional.empty();
        }

        try {
            Class<?> type = Class.forName(name, true, loader);

            for (Constructor<?> candidate : type.getConstructors()) {
                if (candidate.getParameterCount() == arguments.length && accepts(candidate, arguments)) {
                    return Optional.of(candidate);
                }
            }

            LOG.debug("{} has no constructor for the game's arguments; it is not fired", name);
        } catch (ClassNotFoundException | LinkageError e) {
            LOG.debug("{} could not be loaded: {}", name, e.toString());
        }

        return Optional.empty();
    }

    private static boolean accepts(Constructor<?> constructor, Object[] arguments) {
        Class<?>[] parameters = constructor.getParameterTypes();

        for (int i = 0; i < parameters.length; i++) {
            if (arguments[i] != null && !parameters[i].isPrimitive() && !parameters[i].isInstance(arguments[i])) {
                return false;
            }
        }

        return true;
    }

    private static Object bus(ClassLoader loader) {
        Object found = bus;

        if (found != null) {
            return found;
        }

        try {
            found = Class.forName("net.neoforged.neoforge.common.NeoForge", true, loader).getField("EVENT_BUS")
                    .get(null);
            bus = found;
            return found;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** NeoForge's record of the running server, which its patched server keeps and mods ask for. */
    private static void currentServer(Object anchor, Object server) {
        try {
            Class<?> hooks = Class.forName("net.neoforged.neoforge.server.ServerLifecycleHooks", true,
                    anchor.getClass().getClassLoader());
            java.lang.reflect.Field field = hooks.getDeclaredField("currentServer");
            field.setAccessible(true);
            field.set(null, server);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            LOG.debug("could not record the running server for NeoForge: {}", e.toString());
        }
    }

    private static void report(String eventName, Throwable e) {
        if (REPORTED.add(eventName + ":" + e.getClass().getName())) {
            LOG.warn("posting {} failed: {}", eventName.substring(eventName.lastIndexOf('.') + 1),
                    Failures.describe(e));
            OctoLog.detail(e);
        }
    }

    /** Test hook: forgets the bus and every constructor found. */
    static void reset() {
        CONSTRUCTORS.clear();
        REPORTED.clear();
        bus = null;

        synchronized (STARTED) {
            STARTED.clear();
        }
    }
}
