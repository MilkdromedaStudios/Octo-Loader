package studios.milkdromeda.octo.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Makes the record an interface became, from an object written against the
 * interface — for {@link studios.milkdromeda.octo.transform.VerifierFit}.
 *
 * <p>{@code RecipeSerializer} was an interface with {@code codec()} and
 * {@code streamCodec()}; since 26.x it is a record of exactly those two.
 * Every recipe serializer Create has implements the interface, and was a
 * {@code ClassCastException} where it was handed to the registry, so not one
 * of Create's recipe types could be registered. The object still answers the
 * record's components by name, so the record is built from them: one per
 * object, so that the same serializer is always the same record.
 */
public final class RecordAdapter {
    private static final OctoLog LOG = OctoLog.of(RecordAdapter.class);
    private static final Map<Object, Object> ADAPTED = new WeakHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private RecordAdapter() {
    }

    /**
     * @return {@code value} if it already is a {@code record}, else the record
     *         built from its accessors
     * @throws ClassCastException as the cast this stands in for would, when
     *                            the record cannot be built
     */
    public static Object adapt(Object value, Class<?> record) {
        if (value == null || record.isInstance(value)) {
            return value;
        }

        synchronized (ADAPTED) {
            Object made = ADAPTED.get(value);

            if (made != null) {
                return made;
            }
        }

        RecordComponent[] components = record.getRecordComponents();

        if (components == null) {
            throw refused(value, record, "it is not a record");
        }

        Class<?>[] types = new Class<?>[components.length];
        Object[] values = new Object[components.length];

        try {
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                Method accessor = accessor(value.getClass(), components[i].getName());

                if (accessor == null) {
                    throw refused(value, record, "it has no " + components[i].getName() + "()");
                }

                values[i] = accessor.invoke(value);
            }

            Constructor<?> constructor = record.getDeclaredConstructor(types);
            constructor.setAccessible(true);
            Object made = constructor.newInstance(values);

            synchronized (ADAPTED) {
                Object raced = ADAPTED.putIfAbsent(value, made);
                made = raced != null ? raced : made;
            }

            if (REPORTED.add(value.getClass().getName() + ">" + record.getName())) {
                LOG.info("{} stands in as a {}, built from what it answers", value.getClass().getName(),
                        record.getName());
            }

            return made;
        } catch (ClassCastException e) {
            throw e;
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            throw refused(value, record, Failures.describe(Failures.unwrap(e)));
        }
    }

    private static Method accessor(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException | RuntimeException e) {
                // Declared further up, or on an interface.
            }
        }

        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static ClassCastException refused(Object value, Class<?> record, String why) {
        return new ClassCastException(value.getClass().getName() + " cannot be cast to " + record.getName()
                + " and no " + record.getSimpleName() + " could be made from it: " + why);
    }
}
