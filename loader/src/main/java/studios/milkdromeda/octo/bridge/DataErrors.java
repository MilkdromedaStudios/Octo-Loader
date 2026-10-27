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
