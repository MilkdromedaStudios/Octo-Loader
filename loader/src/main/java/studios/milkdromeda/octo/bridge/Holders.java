package studios.milkdromeda.octo.bridge;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import studios.milkdromeda.octo.util.Failures;

/**
 * A registered thing, as the holder of it that the game asks for since 1.20.5.
 *
 * <p>Until then an effect, an attribute or a sound was handed over as itself:
 * {@code entity.hasEffect(MobEffects.POISON)}. Since then the constants are
 * holders and the methods take holders, and a mod built before reaches for a
 * field of a type it no longer has and a method that takes something else.
 * Calls of that kind are sent through here: the value is looked up in the
 * registry that has it and its holder handed on.
 */
public final class Holders {
    /** The registry each kind of value was last found in, by the value's class. */
    private static final Map<Class<?>, Object> REGISTRY_OF = new ConcurrentHashMap<>();

    private Holders() {
    }

    /** @return the value's holder; the value itself when it is one already or is null */
    public static Object of(Object value) {
        if (value == null) {
            return null;
        }

        try {
            ClassLoader loader = value.getClass().getClassLoader();
            Class<?> holder = Class.forName("net.minecraft.core.Holder", false, loader);

            if (holder.isInstance(value)) {
                return value;
            }

            Class<?> registryType = Class.forName("net.minecraft.core.Registry", false, loader);
            Method keyOf = registryType.getMethod("getResourceKey", Object.class);
            Method wrap = registryType.getMethod("wrapAsHolder", Object.class);
            Object known = REGISTRY_OF.get(value.getClass());

            if (known != null && ((Optional<?>) keyOf.invoke(known, value)).isPresent()) {
                return wrap.invoke(known, value);
            }

            Object root = Class.forName("net.minecraft.core.registries.BuiltInRegistries", true, loader)
                    .getField("REGISTRY").get(null);

            for (Object registry : (Iterable<?>) root) {
                try {
                    if (((Optional<?>) keyOf.invoke(registry, value)).isPresent()) {
                        REGISTRY_OF.put(value.getClass(), registry);
                        return wrap.invoke(registry, value);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // A registry of something else entirely: not this one.
                }
            }

            // In no registry: a holder of its own.
            return holder.getMethod("direct", Object.class).invoke(null, value);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            return value;
        }
    }
}
