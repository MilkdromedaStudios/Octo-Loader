package studios.milkdromeda.octo.transform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.objectweb.asm.Type;

/**
 * Members a library removed, rebuilt from what it has now.
 *
 * <p>Some removals are not renames and have no new shape a call can simply be
 * pointed at, but the old behaviour is a line or two of the new API.
 * DataFixerUpper is the one every mod touches: {@code DataResult.get()},
 * which handed back an {@code Either} of the value or the error, went in 1.20.5
 * along with {@code getOrThrow(boolean, Consumer)} and {@code error(String)},
 * and codec code written before then calls all three. A default there is a
 * null {@code Either} one call later — Jade's config codecs failed on exactly
 * that — where the shim answers what the old method did.
 *
 * <p>A shim is a public static method here. It takes the receiver first, as
 * {@code Object}, for an instance method, and the owner's {@code Class} last
 * for a static one; references are passed as {@code Object} and results come
 * back as {@code Object}, cast to what the caller expects at the call site.
 * The library is reached by reflection, since Octo is not built against it.
 */
public final class Shims {
    private static final String OWNER = "studios/milkdromeda/octo/transform/Shims";

    private static final String ENUM_PROPERTY = "net/minecraft/world/level/block/state/properties/EnumProperty";
    private static final String MAKES_ENUM_PROPERTY = ")L" + ENUM_PROPERTY + ";";

    /** What the mod called, in today's class names, to the shim that answers it. */
    private static final Map<String, String> SHIMS = Map.of(
            "com/mojang/serialization/DataResult.get()Lcom/mojang/datafixers/util/Either;", "dataResultGet",
            "com/mojang/serialization/DataResult.getOrThrow(ZLjava/util/function/Consumer;)Ljava/lang/Object;",
            "dataResultGetOrThrow",
            "com/mojang/serialization/DataResult.error(Ljava/lang/String;)Lcom/mojang/serialization/DataResult;",
            "dataResultError",
            "com/mojang/serialization/DataResult.error(Ljava/lang/String;Ljava/lang/Object;)"
                    + "Lcom/mojang/serialization/DataResult;",
            "dataResultErrorWithPartial",
            // DirectionProperty's factories, called on EnumProperty once the
            // class itself has been relinked there.
            ENUM_PROPERTY + ".create(Ljava/lang/String;" + MAKES_ENUM_PROPERTY, "directionProperty",
            ENUM_PROPERTY + ".create(Ljava/lang/String;Ljava/util/function/Predicate;" + MAKES_ENUM_PROPERTY,
            "directionPropertyWhere",
            ENUM_PROPERTY + ".create(Ljava/lang/String;[Lnet/minecraft/core/Direction;" + MAKES_ENUM_PROPERTY,
            "directionPropertyOf",
            ENUM_PROPERTY + ".create(Ljava/lang/String;Ljava/util/Collection;" + MAKES_ENUM_PROPERTY,
            "directionPropertyFrom");

    private Shims() {
    }

    /** @return the shim's name for this member, or {@code null} when there is none */
    static String shimFor(String owner, String name, String descriptor) {
        return SHIMS.get(owner + "." + name + descriptor);
    }

    static String owner() {
        return OWNER;
    }

    /** The shim's own descriptor, by the convention above. */
    static String descriptorFor(String descriptor, boolean isStatic) {
        StringBuilder out = new StringBuilder("(");

        if (!isStatic) {
            out.append("Ljava/lang/Object;");
        }

        for (Type argument : Type.getArgumentTypes(descriptor)) {
            out.append(erased(argument));
        }

        if (isStatic) {
            out.append("Ljava/lang/Class;");
        }

        return out.append(')').append(erased(Type.getReturnType(descriptor))).toString();
    }

    private static String erased(Type type) {
        return type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY ? "Ljava/lang/Object;"
                : type.getDescriptor();
    }

    // ------------------------------------------------------------ DataFixerUpper

    /** {@code DataResult.get()}: the value on the left, or the error on the right. */
    public static Object dataResultGet(Object result) {
        Optional<?> value = (Optional<?>) call(result, "result");

        if (value.isPresent()) {
            return either(result, "left", value.get());
        }

        Optional<?> error = (Optional<?>) call(result, "error");
        return either(result, "right", error.orElse(null));
    }

    /**
     * {@code DataResult.getOrThrow(allowPartial, onError)}: the value, or the
     * error reported and then the partial value if allowed, or an exception.
     */
    @SuppressWarnings("unchecked")
    public static Object dataResultGetOrThrow(Object result, boolean allowPartial, Object onError) {
        Optional<?> value = (Optional<?>) call(result, "result");

        if (value.isPresent()) {
            return value.get();
        }

        Optional<?> error = (Optional<?>) call(result, "error");
        String message = error.map(found -> String.valueOf(call(found, "message"))).orElse("unknown error");

        if (onError instanceof Consumer<?> consumer) {
            ((Consumer<String>) consumer).accept(message);
        }

        if (allowPartial && error.isPresent()) {
            Optional<?> partial = (Optional<?>) call(error.get(), "partialValue");

            if (partial.isPresent()) {
                return partial.get();
            }
        }

        throw new RuntimeException(message);
    }

    /** {@code DataResult.error(String)}, now {@code error(Supplier)}. */
    public static Object dataResultError(Object message, Class<?> owner) {
        return callStatic(owner, "error", new Class<?>[] { Supplier.class },
                (Supplier<String>) () -> String.valueOf(message));
    }

    /** {@code DataResult.error(String, partial)}, now {@code error(Supplier, partial)}. */
    public static Object dataResultErrorWithPartial(Object message, Object partial, Class<?> owner) {
        return callStatic(owner, "error", new Class<?>[] { Supplier.class, Object.class },
                (Supplier<String>) () -> String.valueOf(message), partial);
    }

    private static Object either(Object context, String side, Object value) {
        try {
            Class<?> either = Class.forName("com.mojang.datafixers.util.Either", false,
                    context.getClass().getClassLoader());
            return either.getMethod(side, Object.class).invoke(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("DataFixerUpper's Either is not reachable", e);
        }
    }

    // ------------------------------------------------------ block state properties

    /**
     * {@code DirectionProperty.create(name)}, every direction. DirectionProperty
     * went in 1.21.2, when a property of directions became an
     * {@code EnumProperty<Direction>} like any other; restored, it would extend
     * a class that is final now. Its factories are EnumProperty's with the enum
     * class filled in.
     */
    public static Object directionProperty(Object name, Class<?> owner) {
        return callStatic(owner, "create", new Class<?>[] { String.class, Class.class }, name, direction(owner));
    }

    /** {@code DirectionProperty.create(name, filter)}. */
    public static Object directionPropertyWhere(Object name, Object filter, Class<?> owner) {
        return callStatic(owner, "create", new Class<?>[] { String.class, Class.class, Predicate.class }, name,
                direction(owner), filter);
    }

    /** {@code DirectionProperty.create(name, directions...)}. */
    public static Object directionPropertyOf(Object name, Object directions, Class<?> owner) {
        return callStatic(owner, "create", new Class<?>[] { String.class, Class.class, Enum[].class }, name,
                direction(owner), directions);
    }

    /** {@code DirectionProperty.create(name, directions)}, from any collection. */
    public static Object directionPropertyFrom(Object name, Object directions, Class<?> owner) {
        return callStatic(owner, "create", new Class<?>[] { String.class, Class.class, List.class }, name,
                direction(owner), new ArrayList<>((Collection<?>) directions));
    }

    private static Class<?> direction(Class<?> context) {
        try {
            return Class.forName("net.minecraft.core.Direction", false, context.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the game's Direction is not reachable", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A public no-argument method, looked up on the public type that declares it. */
    private static Object call(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            Method found = publicMethod(type, name);

            if (found != null) {
                return invoke(found, target);
            }
        }

        throw new IllegalStateException(target.getClass().getName() + " has no " + name + "()");
    }

    private static Method publicMethod(Class<?> type, String name) {
        for (Class<?> iface : type.getInterfaces()) {
            try {
                return iface.getMethod(name);
            } catch (NoSuchMethodException e) {
                Method inherited = publicMethod(iface, name);

                if (inherited != null) {
                    return inherited;
                }
            }
        }

        try {
            Method method = type.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    private static Object callStatic(Class<?> owner, String name, Class<?>[] parameters, Object... arguments) {
        try {
            return invoke(owner.getMethod(name, parameters), null, arguments);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(owner.getName() + " has no " + name, e);
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();

            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }

            if (cause instanceof Error error) {
                throw error;
            }

            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
