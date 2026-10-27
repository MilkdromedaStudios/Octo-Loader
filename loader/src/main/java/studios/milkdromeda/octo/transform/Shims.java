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
    private static final String NBT = "net/minecraft/nbt/";
    private static final String NBT_UTILS = NBT + "NbtUtils";
    private static final String COMPOUND = NBT + "CompoundTag";

    private static final Map<String, String> SHIMS = Map.ofEntries(
            Map.entry("com/mojang/serialization/DataResult.get()Lcom/mojang/datafixers/util/Either;", "dataResultGet"),
            Map.entry("com/mojang/serialization/DataResult.getOrThrow(ZLjava/util/function/Consumer;)Ljava/lang/Object;",
                    "dataResultGetOrThrow"),
            Map.entry("com/mojang/serialization/DataResult.error(Ljava/lang/String;)"
                    + "Lcom/mojang/serialization/DataResult;", "dataResultError"),
            Map.entry("com/mojang/serialization/DataResult.error(Ljava/lang/String;Ljava/lang/Object;)"
                    + "Lcom/mojang/serialization/DataResult;", "dataResultErrorWithPartial"),
            // DirectionProperty's factories, called on EnumProperty once the
            // class itself has been relinked there.
            Map.entry(ENUM_PROPERTY + ".create(Ljava/lang/String;" + MAKES_ENUM_PROPERTY, "directionProperty"),
            Map.entry(ENUM_PROPERTY + ".create(Ljava/lang/String;Ljava/util/function/Predicate;"
                    + MAKES_ENUM_PROPERTY, "directionPropertyWhere"),
            Map.entry(ENUM_PROPERTY + ".create(Ljava/lang/String;[Lnet/minecraft/core/Direction;"
                    + MAKES_ENUM_PROPERTY, "directionPropertyOf"),
            Map.entry(ENUM_PROPERTY + ".create(Ljava/lang/String;Ljava/util/Collection;" + MAKES_ENUM_PROPERTY,
                    "directionPropertyFrom"),
            // The NBT helpers 1.21.5 dropped for codecs.
            Map.entry(NBT_UTILS + ".writeBlockPos(Lnet/minecraft/core/BlockPos;)L" + NBT + "Tag;", "nbtWriteBlockPos"),
            Map.entry(NBT_UTILS + ".readBlockPos(L" + COMPOUND + ";Ljava/lang/String;)Ljava/util/Optional;",
                    "nbtReadBlockPos"),
            Map.entry(NBT_UTILS + ".createUUID(Ljava/util/UUID;)L" + NBT + "IntArrayTag;", "nbtCreateUuid"),
            Map.entry(NBT_UTILS + ".loadUUID(L" + NBT + "Tag;)Ljava/util/UUID;", "nbtLoadUuid"),
            Map.entry(COMPOUND + ".putUUID(Ljava/lang/String;Ljava/util/UUID;)V", "compoundPutUuid"),
            Map.entry(COMPOUND + ".getUUID(Ljava/lang/String;)Ljava/util/UUID;", "compoundGetUuid"),
            Map.entry(COMPOUND + ".hasUUID(Ljava/lang/String;)Z", "compoundHasUuid"),
            Map.entry(COMPOUND + ".putIntArray(Ljava/lang/String;Ljava/util/List;)V", "compoundPutIntList"),
            Map.entry(NBT + "Tag.getAsString()Ljava/lang/String;", "tagAsString"),
            Map.entry(NBT + "StringTag.getAsString()Ljava/lang/String;", "tagAsString"),
            Map.entry(NBT + "CompoundTag.getAsString()Ljava/lang/String;", "tagAsString"),
            Map.entry(NBT + "TagParser.parseTag(Ljava/lang/String;)L" + COMPOUND + ";", "tagParserParseTag"));

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

    // ------------------------------------------------------------------- NBT

    /** {@code NbtUtils.writeBlockPos(pos)}: the position as an int array of x, y and z. */
    public static Object nbtWriteBlockPos(Object pos, Class<?> owner) {
        return intArrayTag(owner, new int[] { (int) call(pos, "getX"), (int) call(pos, "getY"),
                (int) call(pos, "getZ") });
    }

    /** {@code NbtUtils.readBlockPos(tag, key)}: the position written that way, if there is one. */
    public static Object nbtReadBlockPos(Object tag, Object key, Class<?> owner) {
        Optional<?> array = (Optional<?>) callWith(tag, "getIntArray", new Class<?>[] { String.class }, key);
        return array.filter(found -> ((int[]) found).length == 3).map(found -> {
            int[] xyz = (int[]) found;
            return construct(game(owner, "net.minecraft.core.BlockPos"),
                    new Class<?>[] { int.class, int.class, int.class }, xyz[0], xyz[1], xyz[2]);
        });
    }

    /** {@code NbtUtils.createUUID(uuid)}: the four ints of it. */
    public static Object nbtCreateUuid(Object uuid, Class<?> owner) {
        return intArrayTag(owner, uuidInts(owner, uuid));
    }

    /** {@code NbtUtils.loadUUID(tag)}: the UUID in an int array tag, or null. */
    public static Object nbtLoadUuid(Object tag, Class<?> owner) {
        return uuidOf(owner, tag);
    }

    /** {@code CompoundTag.putUUID(key, uuid)}. */
    public static void compoundPutUuid(Object tag, Object key, Object uuid) {
        callWith(tag, "putIntArray", new Class<?>[] { String.class, int[].class }, key,
                uuidInts(tag.getClass(), uuid));
    }

    /** {@code CompoundTag.getUUID(key)}: the UUID put there, or null. */
    public static Object compoundGetUuid(Object tag, Object key) {
        return uuidOf(tag.getClass(), callWith(tag, "get", new Class<?>[] { String.class }, key));
    }

    /** {@code CompoundTag.hasUUID(key)}. */
    public static boolean compoundHasUuid(Object tag, Object key) {
        return compoundGetUuid(tag, key) != null;
    }

    /** {@code CompoundTag.putIntArray(key, list)}. */
    public static void compoundPutIntList(Object tag, Object key, Object values) {
        int[] array = ((List<?>) values).stream().mapToInt(value -> ((Number) value).intValue()).toArray();
        callWith(tag, "putIntArray", new Class<?>[] { String.class, int[].class }, key, array);
    }

    /** {@code Tag.getAsString()}: a string tag's string, and anything else as written in commands. */
    public static Object tagAsString(Object tag) {
        Optional<?> string = (Optional<?>) call(tag, "asString");
        return string.isPresent() ? string.get() : String.valueOf(tag);
    }

    /** {@code TagParser.parseTag(text)}, now {@code parseCompoundFully}. */
    public static Object tagParserParseTag(Object text, Class<?> owner) {
        return callStatic(owner, "parseCompoundFully", new Class<?>[] { String.class }, text);
    }

    private static Object intArrayTag(Class<?> context, int[] values) {
        return construct(game(context, "net.minecraft.nbt.IntArrayTag"), new Class<?>[] { int[].class },
                (Object) values);
    }

    private static int[] uuidInts(Class<?> context, Object uuid) {
        return (int[]) callStatic(game(context, "net.minecraft.core.UUIDUtil"), "uuidToIntArray",
                new Class<?>[] { java.util.UUID.class }, uuid);
    }

    private static Object uuidOf(Class<?> context, Object tag) {
        if (tag == null || !tag.getClass().getName().equals("net.minecraft.nbt.IntArrayTag")) {
            return null;
        }

        int[] values = (int[]) call(tag, "getAsIntArray");
        return values.length != 4 ? null : callStatic(game(context, "net.minecraft.core.UUIDUtil"),
                "uuidFromIntArray", new Class<?>[] { int[].class }, (Object) values);
    }

    private static Class<?> game(Class<?> context, String name) {
        try {
            return Class.forName(name, true, context.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("the game's " + name + " is not reachable", e);
        }
    }

    private static Object construct(Class<?> type, Class<?>[] parameters, Object... arguments) {
        try {
            return type.getConstructor(parameters).newInstance(arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getName() + " could not be made", e);
        }
    }

    /** A public method with parameters, on the receiver's class. */
    private static Object callWith(Object target, String name, Class<?>[] parameters, Object... arguments) {
        try {
            Method method = target.getClass().getMethod(name, parameters);
            method.setAccessible(true);
            return invoke(method, target, arguments);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(target.getClass().getName() + " has no " + name, e);
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
