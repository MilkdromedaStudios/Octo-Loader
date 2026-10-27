package studios.milkdromeda.octo.bridge;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Hands a mod from before 1.21.6 the {@code CompoundTag} its save and load
 * methods take, for {@link studios.milkdromeda.octo.transform.ValueIoBridge}.
 *
 * <p>The game writes block entities and entities through {@code ValueOutput}
 * and reads them through {@code ValueInput} now; on disk and over the network
 * both are a {@code CompoundTag}, wrapped. A load gets the tag being read; a
 * save gets an empty tag to fill, which is then merged into the one being
 * written.
 */
public final class ValueIo {
    private static final OctoLog LOG = OctoLog.of(ValueIo.class);
    private static final Map<String, Field> TAGS = new ConcurrentHashMap<>();
    /** The registries of the last world read, for a save whose owner has no world of its own yet. */
    private static volatile Object lastLookup;
    private static volatile boolean warned;

    private ValueIo() {
    }

    /** The tag a {@code ValueInput} reads from, or an empty one. */
    public static Object tag(Object input) {
        try {
            lastLookup = input.getClass().getMethod("lookup").invoke(input);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
        }

        Object tag = wrapped(input, "input");
        return tag != null ? tag : newTag(input);
    }

    /** Writes what the mod saved into the tag a {@code ValueOutput} is building. */
    public static void store(Object output, Object saved) {
        Object tag = wrapped(output, "output");

        if (tag == null || saved == null) {
            if (!warned) {
                warned = true;
                LOG.warn("a mod's saved data could not be written through {}; it is not kept",
                        output == null ? null : output.getClass().getName());
            }

            return;
        }

        try {
            tag.getClass().getMethod("merge", tag.getClass()).invoke(tag, saved);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.debug("saved data could not be merged: {}", e.toString());
        }
    }

    /** The registries a save is made with: its owner's world's, or the last ones read. */
    public static Object registries(Object owner, Object output) {
        for (String accessor : new String[] { "getLevel", "level" }) {
            try {
                Object level = owner.getClass().getMethod(accessor).invoke(owner);

                if (level != null) {
                    return level.getClass().getMethod("registryAccess").invoke(level);
                }
            } catch (NoSuchMethodException e) {
                // The other accessor, then.
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
            }
        }

        return lastLookup;
    }

    private static Object wrapped(Object io, String name) {
        if (io == null) {
            return null;
        }

        Field field = TAGS.computeIfAbsent(io.getClass().getName() + "." + name, ignored -> {
            for (Class<?> type = io.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    Field found = type.getDeclaredField(name);

                    if (found.getType().getName().equals("net.minecraft.nbt.CompoundTag")) {
                        found.setAccessible(true);
                        return found;
                    }
                } catch (NoSuchFieldException | RuntimeException e) {
                    // Declared further up, or not at all.
                }
            }

            return null;
        });

        try {
            return field == null ? null : field.get(io);
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static Object newTag(Object context) {
        try {
            return Class.forName("net.minecraft.nbt.CompoundTag", true, context.getClass().getClassLoader())
                    .getConstructor().newInstance();
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            return null;
        }
    }
}
