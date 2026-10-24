package studios.milkdromeda.octo.transform;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * What a mod from another version calls when the constructor it compiled
 * against is gone.
 *
 * <p>{@code new Vec3(vector3f)} is a {@code NoSuchMethodError} on a version
 * whose {@code Vec3} takes something else, and a constructor cannot be skipped
 * the way a method can: the caller goes on to use the object. So the object is
 * made with what this version does have — the constructor its arguments fit
 * best, the rest of the parameters left at their defaults — and only when
 * nothing at all will make one is the answer null.
 */
public final class GapBridge {
    private static final OctoLog LOG = OctoLog.of(GapBridge.class);
    private static final Set<String> CONTAINED = ConcurrentHashMap.newKeySet();

    private GapBridge() {
    }

    /**
     * Called from the handler {@link CallbackGuard} wraps round a translated
     * mod's callbacks: decides whether what the callback threw stops there.
     *
     * <p>What stops is what translation causes — a member or class this
     * version lacks, a default that turned out to be null — and the game
     * carries on without that one callback. A checked exception is the
     * callback's contract with its caller, a command's syntax error say, and
     * goes on to the caller as written; a JVM in trouble is not a mod's
     * problem to contain.
     */
    public static void contain(Throwable thrown, String site) {
        if (Failures.isFatal(thrown) || !(thrown instanceof RuntimeException || thrown instanceof Error)) {
            throw GapBridge.<RuntimeException>sneaky(thrown);
        }

        if (CONTAINED.add(site)) {
            LOG.warn("{} failed and was skipped so the game can go on: {}", site, Failures.describe(thrown));
            OctoLog.detail(thrown);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable thrown) throws T {
        throw (T) thrown;
    }

    /** Called from the code {@link MissingMemberTransformer} writes in place of the missing constructor. */
    public static Object construct(Class<?> type, Object[] arguments) {
        if (type.isInterface() || Modifier.isAbstract(type.getModifiers())) {
            return null;
        }

        Constructor<?>[] constructors = type.getDeclaredConstructors();
        // Every argument used first, then the fewest made-up parameters.
        Arrays.sort(constructors, Comparator.comparingInt((Constructor<?> c) -> -used(c, arguments))
                .thenComparingInt(Constructor::getParameterCount));

        for (Constructor<?> constructor : constructors) {
            try {
                constructor.setAccessible(true);
                return constructor.newInstance(fill(constructor.getParameterTypes(), arguments));
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.debug("{} would not construct through {}: {}", type.getName(), constructor, e.toString());
            }
        }

        LOG.debug("nothing would construct {}; handing back null", type.getName());
        return null;
    }

    /** How many of the caller's arguments this constructor can take, in order. */
    private static int used(Constructor<?> constructor, Object[] arguments) {
        Class<?>[] parameters = constructor.getParameterTypes();
        int used = 0;
        int next = 0;

        for (Class<?> parameter : parameters) {
            if (next < arguments.length && fits(parameter, arguments[next])) {
                used++;
                next++;
            }
        }

        return used;
    }

    private static Object[] fill(Class<?>[] parameters, Object[] arguments) {
        Object[] out = new Object[parameters.length];
        int next = 0;

        for (int i = 0; i < parameters.length; i++) {
            if (next < arguments.length && fits(parameters[i], arguments[next])) {
                out[i] = arguments[next++];
            } else {
                out[i] = defaultFor(parameters[i]);
            }
        }

        return out;
    }

    private static boolean fits(Class<?> parameter, Object argument) {
        if (argument == null) {
            return !parameter.isPrimitive();
        }

        return box(parameter).isInstance(argument);
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }

        return switch (type.getName()) {
            case "boolean" -> Boolean.class;
            case "byte" -> Byte.class;
            case "char" -> Character.class;
            case "short" -> Short.class;
            case "int" -> Integer.class;
            case "long" -> Long.class;
            case "float" -> Float.class;
            case "double" -> Double.class;
            default -> Void.class;
        };
    }

    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }

        return switch (type.getName()) {
            case "boolean" -> false;
            case "byte" -> (byte) 0;
            case "char" -> (char) 0;
            case "short" -> (short) 0;
            case "int" -> 0;
            case "long" -> 0L;
            case "float" -> 0f;
            default -> 0d;
        };
    }
}
