package studios.milkdromeda.octo.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Saved data asked for the way it was until 1.21.5: a factory and a name.
 *
 * <p>A mod kept its world data — networks, claims, counters — by handing the
 * level's data storage a {@code SavedData.Factory} (a constructor, a reader
 * from a compound tag) and a file name, and the data wrote itself back
 * through its own {@code save(CompoundTag, HolderLookup.Provider)}. The game
 * now asks for a {@code SavedDataType}: an id, a constructor and a codec.
 * One is made here from the factory, its codec reading and writing through
 * the mod's own two methods, and kept by name, since the storage caches data
 * by its type and a second type for the same name would read the file again
 * and lose what the first had changed.
 */
public final class SavedDataFactories {
    private static final OctoLog LOG = OctoLog.of(SavedDataFactories.class);
    private static final String TYPE = "net.minecraft.world.level.saveddata.SavedDataType";

    private static final Map<String, Object> TYPES = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    /** The registries of the storage last asked, which the mods' readers and writers are given. */
    private static volatile Object registries;

    private SavedDataFactories() {
    }

    public static Object computeIfAbsent(Object storage, Object factory, String name) {
        return call(storage, "computeIfAbsent", factory, name);
    }

    public static Object get(Object storage, Object factory, String name) {
        return call(storage, "get", factory, name);
    }

    private static Object call(Object storage, String method, Object factory, String name) {
        if (storage == null || factory == null || name == null) {
            return null;
        }

        try {
            registries = field(storage, "registries");
            ClassLoader loader = storage.getClass().getClassLoader();
            Object type = TYPES.computeIfAbsent(name, ignored -> {
                try {
                    return type(loader, factory, name);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
            Method found = storage.getClass().getMethod(method, Class.forName(TYPE, false, loader));
            return found.invoke(storage, type);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            Throwable cause = Failures.unwrap(e);

            if (REPORTED.add(name)) {
                LOG.warn("saved data {} could not be {}: {}", name, method.equals("get") ? "read" : "made",
                        Failures.describe(cause));
                OctoLog.detail(cause);
            }

            // computeIfAbsent never answered null: a fresh one, unsaved.
            return method.equals("get") ? null : fresh(factory);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Object type(ClassLoader loader, Object factory, String name) throws ReflectiveOperationException {
        Supplier<Object> constructor = (Supplier<Object>) accessor(factory, "constructor");
        BiFunction<Object, Object, Object> reader = (BiFunction<Object, Object, Object>) accessor(factory,
                "deserializer");
        Object fixType = accessor(factory, "type");
        Class<?> fixTypes = Class.forName("net.minecraft.util.datafix.DataFixTypes", true, loader);

        Class<?> compound = Class.forName("net.minecraft.nbt.CompoundTag", true, loader);
        Object compoundCodec = compound.getField("CODEC").get(null);
        Class<?> codecType = Class.forName("com.mojang.serialization.Codec", false, loader);
        Function<Object, Object> read = tag -> reader.apply(tag, registries);
        Function<Object, Object> write = data -> save(data, compound);
        Object codec = codecType.getMethod("xmap", Function.class, Function.class).invoke(compoundCodec, read, write);

        if (fixType == null) {
            // The old storage read a file without one as it was; the new one
            // fixes every file, so it is given the type that changes least.
            fixType = Enum.valueOf((Class<? extends Enum>) fixTypes, "SAVED_DATA_COMMAND_STORAGE");
        }

        Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", true, loader);
        Object id = identifier.getMethod("parse", String.class).invoke(null, idOf(name));
        Class<?> type = Class.forName(TYPE, true, loader);
        return type.getConstructor(identifier, Supplier.class, codecType, fixTypes)
                .newInstance(id, constructor, codec, fixType);
    }

    /** {@code createaddition_networks} as an id: its own namespace if it has one, the game's if not. */
    static String idOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : clean(lower.substring(0, colon), false);
        String path = clean(colon < 0 ? lower : lower.substring(colon + 1), true);
        return (namespace.isEmpty() ? "minecraft" : namespace) + ":" + (path.isEmpty() ? "data" : path);
    }

    private static String clean(String part, boolean path) {
        StringBuilder out = new StringBuilder(part.length());

        for (char c : part.toCharArray()) {
            boolean allowed = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_' || c == '-' || c == '.'
                    || path && c == '/';
            out.append(allowed ? c : '_');
        }

        return out.toString();
    }

    /** The mod's own {@code save(CompoundTag, HolderLookup.Provider)}, into a fresh tag. */
    private static Object save(Object data, Class<?> compound) {
        try {
            Object tag = compound.getConstructor().newInstance();

            for (Class<?> at = data.getClass(); at != null; at = at.getSuperclass()) {
                for (Method method : at.getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();

                    if (method.getName().equals("save") && parameters.length == 2 && parameters[0] == compound) {
                        method.setAccessible(true);
                        Object written = method.invoke(data, tag, registries);
                        return compound.isInstance(written) ? written : tag;
                    }
                }
            }

            return tag;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not save " + data.getClass().getName(), Failures.unwrap(e));
        }
    }

    private static Object fresh(Object factory) {
        try {
            return ((Supplier<?>) accessor(factory, "constructor")).get();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Object accessor(Object owner, String name) throws ReflectiveOperationException {
        Method method = owner.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(owner);
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(owner);
            } catch (NoSuchFieldException e) {
                // Declared higher up.
            }
        }

        return null;
    }
}
