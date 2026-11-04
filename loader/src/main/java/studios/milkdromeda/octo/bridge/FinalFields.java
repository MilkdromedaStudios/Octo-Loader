package studios.milkdromeda.octo.bridge;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Sets a field of the game's that an older mod wrote to and that is final now,
 * for {@link studios.milkdromeda.octo.transform.FinalFieldWrites}.
 */
public final class FinalFields {
    private static final OctoLog LOG = OctoLog.of(FinalFields.class);
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> WARNED = new ConcurrentHashMap<>();

    private FinalFields() {
    }

    /** {@code owner.field = value}, where {@code field} names the declaring class and the field. */
    public static void set(Object owner, Object value, String field) {
        if (owner == null) {
            throw new NullPointerException("Cannot assign field \"" + field + "\" because the object is null");
        }

        try {
            Field target = FIELDS.computeIfAbsent(owner.getClass().getClassLoader().hashCode() + "/" + field, key -> {
                int dot = field.lastIndexOf('.');

                try {
                    Class<?> type = Class.forName(field.substring(0, dot).replace('/', '.'), false,
                            owner.getClass().getClassLoader());
                    Field found = type.getDeclaredField(field.substring(dot + 1));
                    found.setAccessible(true);
                    return found;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
            target.set(owner, value);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);

            if (WARNED.putIfAbsent(field, true) == null) {
                LOG.warn("{} could not be set as an older mod asked: {}", field.replace('/', '.'),
                        Failures.describe(Failures.unwrap(e)));
            }
        }
    }
}
