package studios.milkdromeda.octo.bridge;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/** For {@link studios.milkdromeda.octo.transform.RegistryLoadGuard}. */
public final class DataErrors {
    private static final OctoLog LOG = OctoLog.of(DataErrors.class);
    private DataErrors() {
    }

    /**
     * What {@code loadFromResource} answers for an entry that could not be
     * read: the error, as its own catch would have made it.
     */
    public static Object entryFailed(Throwable thrown, Object key, Object resource) throws Throwable {
        if (Failures.isFatal(thrown)) {
            throw thrown;
        }

        String id = String.valueOf(call(key, "identifier"));
        String pack = String.valueOf(call(resource, "sourcePackId"));
        Exception error = new IllegalStateException("Failed to parse " + id + " from pack " + pack, thrown);
        Class<?> either = Class.forName("com.mojang.datafixers.util.Either", false, key.getClass().getClassLoader());
        return either.getMethod("right", Object.class).invoke(null, error);
    }

    private static final java.util.Map<String, java.util.concurrent.atomic.AtomicInteger> SKIPPED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Whether an entry that could not be read is left out rather than
     * failing the load: one from the pack of a mod built for another
     * Minecraft. The first from each pack is reported in full.
     */
    public static boolean forgive(Throwable error) {
        String message = error == null ? null : error.getMessage();
        int at = message == null ? -1 : message.lastIndexOf(" from pack mod/");

        if (at < 0) {
            return false;
        }

        String mod = message.substring(at + " from pack mod/".length()).trim();

        try {
            boolean translated = studios.milkdromeda.octo.runtime.OctoRuntime.get().mods().stream()
                    .anyMatch(loaded -> loaded.id().equals(mod) && loaded.isFromAnotherVersion());

            if (!translated) {
                return false;
            }
        } catch (IllegalStateException e) {
            return false;
        }

        int count = SKIPPED.computeIfAbsent(mod, ignored -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();

        if (count == 1) {
            LOG.warn("{} has data this Minecraft cannot read; each such entry is left out, the first because: {}",
                    mod, Failures.describe(Failures.unwrap(error)));
        } else {
            LOG.debug("left out: {} <- {}", message, Failures.describe(Failures.unwrap(error.getCause() == null
                    ? error : error.getCause())));
        }

        return true;
    }

    /**
     * Fills the loot tables a registry was told about and never given, just
     * before it is frozen, with empty ones.
     *
     * <p>A mod's loot table that names another of its tables, one left out
     * because this game could not read it, leaves that name in the registry
     * with nothing behind it, and the game refuses to freeze a registry with
     * such a name in it: two of Supplementaries' urn tables stopped the server.
     * Empty, the urn drops nothing and the world loads. Only names from mods
     * are filled; one of the game's own missing is a real fault.
     */
    public static void fillUnbound(Object registry) {
        try {
            Object registryKey = registry.getClass().getMethod("key").invoke(registry);

            if (!"loot_table".equals(pathOf(registryKey))) {
                return;
            }

            // Named and never registered: a registered entry is only bound
            // to its value by the freeze this runs ahead of.
            java.util.Map<?, ?> byKey = (java.util.Map<?, ?>) field(registry, "byKey");
            java.util.Map<?, ?> byLocation = (java.util.Map<?, ?>) field(registry, "byLocation");
            java.util.List<Object> unbound = new java.util.ArrayList<>();

            for (Object key : byKey.keySet()) {
                if (!byLocation.containsKey(idOf(key)) && !"minecraft".equals(namespaceOf(key))) {
                    unbound.add(key);
                }
            }

            if (unbound.isEmpty()) {
                return;
            }

            ClassLoader loader = registry.getClass().getClassLoader();
            Class<?> lootTable = Class.forName("net.minecraft.world.level.storage.loot.LootTable", true, loader);
            Class<?> info = Class.forName("net.minecraft.core.RegistrationInfo", true, loader);
            Object builtIn = info.getField("BUILT_IN").get(null);
            java.lang.reflect.Method register = null;

            for (java.lang.reflect.Method method : registry.getClass().getMethods()) {
                if (method.getName().equals("register") && method.getParameterCount() == 3
                        && method.getParameterTypes()[2] == info) {
                    register = method;
                }
            }

            if (register == null) {
                return;
            }

            for (Object key : unbound) {
                Object builder = lootTable.getMethod("lootTable").invoke(null);
                Object table = builder.getClass().getMethod("build").invoke(builder);
                register.invoke(registry, key, table, builtIn);
            }

            LOG.warn("{} loot table(s) named by mods' data were never loaded, so they are empty: {}", unbound.size(),
                    unbound.stream().map(DataErrors::idOf).toList());
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("could not fill the unbound entries of {}: {}", registry, Failures.describe(Failures.unwrap(e)));
            OctoLog.detail(Failures.unwrap(e));
        }
    }

    private static Object idOf(Object resourceKey) {
        Object id = callOrNull(resourceKey, "identifier");
        return id == null ? callOrNull(resourceKey, "location") : id;
    }

    private static String pathOf(Object resourceKey) {
        Object id = idOf(resourceKey);
        return id == null ? null : String.valueOf(callOrNull(id, "getPath"));
    }

    private static String namespaceOf(Object resourceKey) {
        Object id = idOf(resourceKey);
        return id == null ? null : String.valueOf(callOrNull(id, "getNamespace"));
    }

    private static Object callOrNull(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                // keep looking up the hierarchy
            }
        }

        throw new NoSuchFieldException(name);
    }

    /** How many entries each translated mod's pack had left out. */
    public static java.util.Map<String, Integer> skipped() {
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        SKIPPED.forEach((mod, count) -> out.put(mod, count.get()));
        return out;
    }

    private static Object call(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return target;
        }
    }
}
