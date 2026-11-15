package studios.milkdromeda.octo.bridge;

import java.lang.reflect.Method;
import java.util.ArrayDeque;

import studios.milkdromeda.octo.util.Failures;

/**
 * An entity's synced data, defined the way mods did until 1.20.5.
 *
 * <p>An entity used to override {@code defineSynchedData()} and call
 * {@code this.entityData.define(key, value)} in it. Since 1.20.5 the game
 * hands the method a builder instead, before the entity has its data at all,
 * and the old method is neither called nor able to work. Octo gives such an
 * entity the new method, which keeps the builder here while the old one runs,
 * and sends every {@code define} the old one makes to it.
 */
public final class EntityData {
    private static final ThreadLocal<ArrayDeque<Object>> BUILDERS = ThreadLocal.withInitial(ArrayDeque::new);

    private EntityData() {
    }

    /** Called by the bridge before it runs the entity's old {@code defineSynchedData()}. */
    public static void enter(Object builder) {
        BUILDERS.get().push(builder);
    }

    public static void exit() {
        ArrayDeque<Object> builders = BUILDERS.get();

        if (!builders.isEmpty()) {
            builders.pop();
        }
    }

    /** {@code entityData.define(key, value)}: defined on the builder in hand. */
    public static void define(Object entityData, Object key, Object value) {
        Object builder = BUILDERS.get().peek();

        if (builder == null) {
            return;
        }

        try {
            for (Method method : builder.getClass().getMethods()) {
                if (method.getName().equals("define") && method.getParameterCount() == 2) {
                    method.invoke(builder, key, value);
                    return;
                }
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Defined already: a mod's entity above this one ran its old
            // method too, as part of this one's.
            if (!(e.getCause() instanceof IllegalArgumentException)) {
                Failures.rethrowIfFatal(e.getCause());
                throw e.getCause() instanceof RuntimeException runtime ? runtime
                        : new IllegalStateException(e.getCause());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the entity's data could not be defined", e);
        }
    }
}
