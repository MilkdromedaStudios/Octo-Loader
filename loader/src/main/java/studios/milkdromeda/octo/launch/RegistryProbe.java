package studios.milkdromeda.octo.launch;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Asks the game's registries whether they actually got filled.
 *
 * <p>An empty registry is the quietest serious failure in a modded launch. The
 * game logs one line about it — {@code Registry 'minecraft:chunk_status' was
 * empty after loading} — carries on for another few seconds, and then dies
 * somewhere else entirely with
 *
 * <pre>Cannot invoke "net.minecraft.core.Holder$Reference.value()" because "this.defaultValue" is null</pre>
 *
 * which names neither the registry nor anything that could be fixed. The two are
 * the same failure: a registry whose contents were never registered has no
 * default entry either, and the crash is whatever asked for that default first.
 *
 * <p>So the registries are read once, immediately after the bootstrap that was
 * supposed to fill them, and anything empty is named while the cause is still in
 * reach. Everything here is reflective and every step is allowed to fail: this
 * has to run on Minecraft versions written after it, and on old ones that have
 * none of these classes, without being the reason a launch stops.
 */
final class RegistryProbe {
    private static final OctoLog LOG = OctoLog.of(RegistryProbe.class);

    /** The registry of registries, newest name first. */
    private static final List<String> ROOTS = List.of(
            "net.minecraft.core.registries.BuiltInRegistries",
            "net.minecraft.core.Registry");

    private RegistryProbe() {
    }

    /**
     * What the registries look like after the bootstrap.
     *
     * @param empty          registries with nothing in them
     * @param missingDefault registries whose declared default was never registered
     *                       — the ones that produce the null-default crash
     */
    record Findings(List<String> empty, List<String> missingDefault) {
        static final Findings NOTHING = new Findings(List.of(), List.of());

        boolean isEmpty() {
            return empty.isEmpty() && missingDefault.isEmpty();
        }

        /** One line per problem, for the log and the report. */
        List<String> describe() {
            List<String> out = new ArrayList<>();

            for (String name : missingDefault) {
                out.add(name + " has no default entry, so anything asking for one gets nothing");
            }

            for (String name : empty) {
                if (!missingDefault.contains(name)) {
                    out.add(name + " is empty");
                }
            }

            return out;
        }
    }

    /**
     * Reads every registry the game has, without disturbing any of them.
     *
     * @return what is wrong, or {@link Findings#NOTHING} when the registries look
     *         built — which is also the answer when this Minecraft is too old to
     *         have them, or too obfuscated to ask
     */
    static Findings inspect(ClassLoader gameLoader) {
        try {
            Class<?> registryType = type(gameLoader, "net.minecraft.core.Registry");

            if (registryType == null) {
                return Findings.NOTHING;
            }

            Object root = rootRegistry(gameLoader);

            if (!(root instanceof Iterable<?> registries)) {
                return Findings.NOTHING;
            }

            Method keySet = registryType.getMethod("keySet");
            Method getKey = registryType.getMethod("getKey", Object.class);
            Class<?> defaultedType = type(gameLoader, "net.minecraft.core.DefaultedRegistry");
            Method getDefaultKey = defaultedType == null ? null : defaultedType.getMethod("getDefaultKey");

            List<String> empty = new ArrayList<>();
            List<String> missingDefault = new ArrayList<>();

            for (Object registry : registries) {
                if (registry == null) {
                    continue;
                }

                Object name = getKey.invoke(root, registry);
                Set<?> keys = keySet.invoke(registry) instanceof Set<?> set ? set : null;

                if (keys == null) {
                    continue;
                }

                if (keys.isEmpty()) {
                    empty.add(String.valueOf(name));
                }

                if (getDefaultKey != null && defaultedType.isInstance(registry)
                        && !keys.contains(getDefaultKey.invoke(registry))) {
                    missingDefault.add(String.valueOf(name));
                }
            }

            if (!empty.isEmpty() || !missingDefault.isEmpty()) {
                LOG.warn("{} registrie(s) came out of the game's bootstrap unusable", empty.size()
                        + missingDefault.size());
            }

            return new Findings(List.copyOf(empty), List.copyOf(missingDefault));
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            // Every version that does not have these classes under these names
            // lands here, and for those there is nothing to check.
            LOG.debug("could not read the game's registries: {}", e.toString());
            return Findings.NOTHING;
        }
    }

    private static Object rootRegistry(ClassLoader gameLoader) {
        for (String owner : ROOTS) {
            Class<?> type = type(gameLoader, owner);

            if (type == null) {
                continue;
            }

            try {
                Field field = type.getDeclaredField("REGISTRY");
                field.setAccessible(true);
                Object value = field.get(null);

                if (value != null) {
                    return value;
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.debug("{} has no usable REGISTRY field: {}", owner, e.toString());
            }
        }

        return null;
    }

    /** Loads a game class, initialising it, or answers null if it is not there. */
    private static Class<?> type(ClassLoader gameLoader, String name) {
        try {
            return Class.forName(name, true, gameLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }
}
