package studios.milkdromeda.octo.bridge.forge;

import java.lang.reflect.Method;
import java.util.Optional;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * An entity's mod data written with it and read back, under the key NeoForge
 * uses, so a world saved by either keeps it ({@code EntityPersistentData}).
 */
public final class EntityData {
    private static final OctoLog LOG = OctoLog.of(EntityData.class);
    private static final String KEY = "NeoForgeData";

    private static volatile boolean reported;

    private EntityData() {
    }

    /** {@code output.store("NeoForgeData", CompoundTag.CODEC, tag)}, when there is anything in it. */
    public static void save(Object tag, Object output) {
        if (tag == null || output == null) {
            return;
        }

        try {
            if ((boolean) tag.getClass().getMethod("isEmpty").invoke(tag)) {
                return;
            }

            Object codec = tag.getClass().getField("CODEC").get(null);
            Method store = method(output.getClass(), "store", 3);
            store.invoke(output, KEY, codec, tag);
        } catch (Throwable e) {
            report(e);
        }
    }

    /** {@code input.read("NeoForgeData", CompoundTag.CODEC).orElse(null)}. */
    public static Object load(Object input) {
        if (input == null) {
            return null;
        }

        try {
            Class<?> compound = Class.forName("net.minecraft.nbt.CompoundTag", false,
                    input.getClass().getClassLoader());
            Object codec = compound.getField("CODEC").get(null);
            Method read = method(input.getClass(), "read", 2);
            Object found = read.invoke(input, KEY, codec);
            return found instanceof Optional<?> optional ? optional.orElse(null) : null;
        } catch (Throwable e) {
            report(e);
            return null;
        }
    }

    /** The interface method taking a String name, a codec and, for {@code store}, the value. */
    private static Method method(Class<?> type, String name, int arity) throws NoSuchMethodException {
        for (Class<?> face : allInterfaces(type)) {
            for (Method method : face.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arity
                        && method.getParameterTypes()[0] == String.class) {
                    return method;
                }
            }
        }

        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static java.util.List<Class<?>> allInterfaces(Class<?> type) {
        java.util.List<Class<?>> out = new java.util.ArrayList<>();

        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            for (Class<?> face : at.getInterfaces()) {
                out.add(face);
            }
        }

        return out;
    }

    private static void report(Throwable e) {
        Failures.rethrowIfFatal(e);

        if (!reported) {
            reported = true;
            LOG.warn("entity mod data could not be saved or read: {}", Failures.describe(Failures.unwrap(e)));
        }
    }
}
