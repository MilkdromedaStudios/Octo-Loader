package studios.milkdromeda.octo.bridge.forge;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * The capability listeners NeoForge's patched {@code ServerLevel} keeps, kept
 * here for the unpatched one.
 *
 * <p>A {@code BlockCapabilityCache} — how a pipe remembers the tank beside it
 * — looks a capability up once and registers a listener to be told when the
 * block at that position changes. Without one the cache never heard: a pipe
 * that looked before the tank was placed remembered nothing there forever,
 * and Create's pumps moved water into no tank. The listeners are held
 * weakly, as NeoForge holds them, and told when a block, or a block entity,
 * changes where they listen.
 */
public final class CapabilityListeners {
    private static final OctoLog LOG = OctoLog.of(CapabilityListeners.class);

    private static final Map<Object, Map<Long, List<WeakReference<Object>>>> BY_LEVEL =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile Method onInvalidate;
    private static volatile Method asLong;
    private static volatile boolean reported;

    private CapabilityListeners() {
    }

    /** {@code ServerLevel.registerCapabilityListener(pos, listener)}. */
    public static void register(Object level, Object pos, Object listener) {
        if (level == null || pos == null || listener == null) {
            return;
        }

        long key = key(pos);

        synchronized (BY_LEVEL) {
            BY_LEVEL.computeIfAbsent(level, ignored -> new HashMap<>())
                    .computeIfAbsent(key, ignored -> new ArrayList<>(2)).add(new WeakReference<>(listener));
        }
    }

    /** {@code ServerLevel.invalidateCapabilities(pos)}, and every block change on a server. */
    public static void invalidate(Object level, Object pos) {
        if (level == null || pos == null || BY_LEVEL.isEmpty()) {
            return;
        }

        List<WeakReference<Object>> listeners;

        synchronized (BY_LEVEL) {
            Map<Long, List<WeakReference<Object>>> byPos = BY_LEVEL.get(level);

            if (byPos == null) {
                return;
            }

            listeners = byPos.remove(key(pos));
        }

        if (listeners == null) {
            return;
        }

        List<WeakReference<Object>> kept = new ArrayList<>(listeners.size());

        for (WeakReference<Object> reference : listeners) {
            Object listener = reference.get();

            if (listener != null && tell(listener)) {
                kept.add(reference);
            }
        }

        if (!kept.isEmpty()) {
            synchronized (BY_LEVEL) {
                BY_LEVEL.computeIfAbsent(level, ignored -> new HashMap<>())
                        .computeIfAbsent(key(pos), ignored -> new ArrayList<>(kept.size())).addAll(kept);
            }
        }
    }

    /** {@code ServerLevel.invalidateCapabilities(chunkPos)}: every listener in the chunk. */
    public static void invalidateChunk(Object level, int chunkX, int chunkZ) {
        List<Long> keys = new ArrayList<>();

        synchronized (BY_LEVEL) {
            Map<Long, List<WeakReference<Object>>> byPos = BY_LEVEL.get(level);

            if (byPos == null) {
                return;
            }

            for (Long key : byPos.keySet()) {
                if ((unpackX(key) >> 4) == chunkX && (unpackZ(key) >> 4) == chunkZ) {
                    keys.add(key);
                }
            }
        }

        for (Long key : keys) {
            invalidateKey(level, key);
        }
    }

    private static void invalidateKey(Object level, long key) {
        List<WeakReference<Object>> listeners;

        synchronized (BY_LEVEL) {
            Map<Long, List<WeakReference<Object>>> byPos = BY_LEVEL.get(level);
            listeners = byPos == null ? null : byPos.remove(key);
        }

        if (listeners != null) {
            for (WeakReference<Object> reference : listeners) {
                Object listener = reference.get();

                if (listener != null && tell(listener)) {
                    register(level, key, listener);
                }
            }
        }
    }

    /** {@code ServerLevel.cleanCapabilityListenerReferences()}: the listeners no one holds any more, forgotten. */
    public static void clean(Object level) {
        synchronized (BY_LEVEL) {
            Map<Long, List<WeakReference<Object>>> byPos = BY_LEVEL.get(level);

            if (byPos == null) {
                return;
            }

            for (Iterator<List<WeakReference<Object>>> lists = byPos.values().iterator(); lists.hasNext(); ) {
                List<WeakReference<Object>> list = lists.next();
                list.removeIf(reference -> reference.get() == null);

                if (list.isEmpty()) {
                    lists.remove();
                }
            }
        }
    }

    private static boolean tell(Object listener) {
        try {
            Method method = onInvalidate;

            if (method == null || !method.getDeclaringClass().isInstance(listener)) {
                method = listenerInterface(listener.getClass()).getMethod("onInvalidate");
                onInvalidate = method;
            }

            return (boolean) method.invoke(listener);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);

            if (!reported) {
                reported = true;
                LOG.warn("a capability listener could not be told its block changed: {}",
                        Failures.describe(Failures.unwrap(e)));
            }

            return false;
        }
    }

    private static Class<?> listenerInterface(Class<?> type) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            for (Class<?> face : at.getInterfaces()) {
                if (face.getName().equals("net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener")) {
                    return face;
                }
            }
        }

        return type;
    }

    private static long key(Object pos) {
        if (pos instanceof Long packed) {
            return packed;
        }

        try {
            Method method = asLong;

            if (method == null) {
                method = pos.getClass().getMethod("asLong");
                asLong = method;
            }

            return (long) method.invoke(pos);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // BlockPos's packing: 26 bits of x, 26 of z, 12 of y.
    private static int unpackX(long packed) {
        return (int) (packed << 64 - 38 - 26 >> 64 - 26);
    }

    private static int unpackZ(long packed) {
        return (int) (packed << 64 - 12 - 26 >> 64 - 26);
    }

    private static volatile Method getLevel;
    private static volatile Method getBlockPos;

    /** A block or block entity changed in a chunk: its level's listeners there are told. */
    public static void changed(Object chunk, Object posOrBlockEntity) {
        if (BY_LEVEL.isEmpty() || chunk == null || posOrBlockEntity == null) {
            return;
        }

        try {
            Method level = getLevel;

            if (level == null || !level.getDeclaringClass().isInstance(chunk)) {
                level = chunk.getClass().getMethod("getLevel");
                getLevel = level;
            }

            Object pos = posOrBlockEntity;

            if (!pos.getClass().getName().startsWith("net.minecraft.core.BlockPos")) {
                Method blockPos = getBlockPos;

                if (blockPos == null || !blockPos.getDeclaringClass().isInstance(pos)) {
                    blockPos = pos.getClass().getMethod("getBlockPos");
                    getBlockPos = blockPos;
                }

                pos = blockPos.invoke(pos);
            }

            invalidate(level.invoke(chunk), pos);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Not a chunk of a level that listens.
        }
    }
}
