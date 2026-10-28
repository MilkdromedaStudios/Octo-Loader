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

    private static final java.util.Map<String, java.lang.reflect.Field> FIELDS = new ConcurrentHashMap<>();

    /**
     * Reads a field the mod may no longer touch, for the code
     * {@link MissingMemberTransformer} writes where there is no accessor.
     *
     * @param target     the object, or {@code null} for a static field
     * @param descriptor the field's type, for the default when it cannot be read
     */
    public static Object readField(Object target, Class<?> owner, String name, String descriptor) {
        try {
            return field(owner, name).get(target);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.debug("{}.{} could not be read: {}", owner.getName(), name, e.toString());
            return switch (descriptor.charAt(0)) {
                case 'Z' -> false;
                case 'B' -> (byte) 0;
                case 'C' -> (char) 0;
                case 'S' -> (short) 0;
                case 'I' -> 0;
                case 'J' -> 0L;
                case 'F' -> 0f;
                case 'D' -> 0d;
                default -> null;
            };
        }
    }

    /** Writes a field the mod may no longer touch; a write that cannot happen goes nowhere. */
    public static void writeField(Object target, Object value, Class<?> owner, String name) {
        try {
            field(owner, name).set(target, value);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.debug("{}.{} could not be written: {}", owner.getName(), name, e.toString());
        }
    }

    private static java.lang.reflect.Field field(Class<?> owner, String name) throws NoSuchFieldException {
        String key = owner.getName() + "." + name;
        java.lang.reflect.Field found = FIELDS.get(key);

        if (found != null) {
            return found;
        }

        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                found = type.getDeclaredField(name);
                found.setAccessible(true);
                FIELDS.put(key, found);
                return found;
            } catch (NoSuchFieldException e) {
                // Declared further up.
            }
        }

        throw new NoSuchFieldException(key);
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

    /** How many of the caller's arguments this constructor can take. */
    private static int used(Constructor<?> constructor, Object[] arguments) {
        int used = 0;

        for (int from : assign(constructor.getParameterTypes(), arguments)) {
            used += from >= 0 ? 1 : 0;
        }

        return used;
    }

    private static Object[] fill(Class<?>[] parameters, Object[] arguments) {
        Object[] out = new Object[parameters.length];
        int[] from = assign(parameters, arguments);

        for (int i = 0; i < parameters.length; i++) {
            out[i] = from[i] >= 0 ? arguments[from[i]] : defaultFor(parameters[i]);
        }

        return out;
    }

    /**
     * Which argument each parameter takes: in order where they line up, and
     * then whatever argument of its type is left, since a constructor's
     * parameters are sometimes only put in another order between versions —
     * 26.x's {@code StandingAndWallBlockItem} takes the direction before the
     * item properties — and in order alone the properties became a null.
     *
     * @return per parameter, the index of its argument, or -1 for a default
     */
    private static int[] assign(Class<?>[] parameters, Object[] arguments) {
        int[] from = new int[parameters.length];
        boolean[] taken = new boolean[arguments.length];
        Arrays.fill(from, -1);
        int next = 0;

        for (int i = 0; i < parameters.length; i++) {
            if (next < arguments.length && fits(parameters[i], arguments[next])) {
                from[i] = next;
                taken[next++] = true;
            }
        }

        for (int i = 0; i < parameters.length; i++) {
            for (int j = 0; j < arguments.length && from[i] < 0; j++) {
                if (!taken[j] && arguments[j] != null && fits(parameters[i], arguments[j])) {
                    from[i] = j;
                    taken[j] = true;
                }
            }
        }

        return from;
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

    private static final java.util.Map<Class<?>, Object> SAMPLES = new ConcurrentHashMap<>();
    private static final Object NO_SAMPLE = new Object();

    /**
     * A value of {@code type} to build something with where nothing was
     * given: its first constant if it is an enum, else the first public
     * constant of its own type it declares. A made-up constructor argument
     * of {@code null} is a crash inside the constructor more often than not —
     * Farmer's Delight's fence gate, built through 26.x's constructor that
     * takes a wood type, failed reading the sounds of a null one, where
     * {@code WoodType.OAK} builds it.
     *
     * @return that value, or {@code null} when the type has none
     */
    public static Object sample(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isInterface() || type.isArray()) {
            return null;
        }

        Object found = SAMPLES.computeIfAbsent(type, ignored -> {
            try {
                if (type.isEnum()) {
                    Object[] constants = type.getEnumConstants();
                    return constants.length == 0 ? NO_SAMPLE : constants[0];
                }

                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    int modifiers = field.getModifiers();

                    if (field.getType() == type && Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers)
                            && Modifier.isFinal(modifiers)) {
                        Object value = field.get(null);

                        if (value != null) {
                            return value;
                        }
                    }
                }
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
            }

            return NO_SAMPLE;
        });

        return found == NO_SAMPLE ? null : found;
    }

    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) {
            return sample(type);
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
