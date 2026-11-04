package studios.milkdromeda.octo.bridge;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * The answers an older mod's block or item gives to a right click, made into
 * the {@code InteractionResult} this Minecraft reads, for
 * {@link studios.milkdromeda.octo.transform.InteractionBridge}.
 *
 * <ul>
 * <li>A block from 1.20.5 to 1.21.1 answers {@code useItemOn} with an
 * {@code ItemInteractionResult}: its values have their counterparts by name,
 * "pass to the default interaction" being "try with an empty hand".</li>
 * <li>A block from before 1.20.5 answers {@code use} with an
 * {@code InteractionResult}; passing then lets the game go on as it did.</li>
 * <li>An item from before 1.21.2 answers {@code use} with an
 * {@code InteractionResultHolder}: its result, holding the item the hand is to
 * hold after.</li>
 * </ul>
 */
public final class Interactions {
    private static final OctoLog LOG = OctoLog.of(Interactions.class);
    private static final String RESULT = "net.minecraft.world.InteractionResult";
    private static final Map<ClassLoader, Map<String, Object>> CONSTANTS = new ConcurrentHashMap<>();
    private static volatile boolean warned;

    private Interactions() {
    }

    /** An {@code ItemInteractionResult} (1.20.5 to 1.21.1) as this Minecraft's result. */
    public static Object fromItemResult(Object self, Object old) {
        String name = old instanceof Enum<?> e ? e.name() : null;
        String now = name == null ? "TRY_WITH_EMPTY_HAND" : switch (name) {
            case "SUCCESS" -> "SUCCESS";
            case "CONSUME", "CONSUME_PARTIAL" -> "CONSUME";
            case "FAIL" -> "FAIL";
            case "SKIP_DEFAULT_BLOCK_INTERACTION" -> "PASS";
            default -> "TRY_WITH_EMPTY_HAND";
        };
        return constant(self, now);
    }

    /** What a block's old {@code use} answered: as is, but passing on to the block's own empty-hand use. */
    public static Object fromBlockUse(Object self, Object old) {
        if (old == null || isPass(old)) {
            return constant(self, "TRY_WITH_EMPTY_HAND");
        }

        return old;
    }

    /** An {@code InteractionResultHolder} (before 1.21.2) as this Minecraft's result. */
    public static Object fromHolder(Object self, Object holder) {
        if (holder == null) {
            return constant(self, "PASS");
        }

        try {
            Object result = method(holder.getClass(), "getResult").invoke(holder);
            Object item = method(holder.getClass(), "getObject").invoke(holder);

            if (result == null) {
                return constant(self, "PASS");
            }

            Class<?> success = Class.forName(RESULT + "$Success", false, self.getClass().getClassLoader());

            if (success.isInstance(result) && item != null) {
                // The hand holds what the item answered with: a filled bucket, a thrown stack's remainder.
                return success.getMethod("heldItemTransformedTo",
                        Class.forName("net.minecraft.world.item.ItemStack", false, self.getClass().getClassLoader()))
                        .invoke(result, item);
            }

            return result;
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            warnOnce(e);
            return constant(self, "PASS");
        }
    }

    private static boolean isPass(Object result) {
        return result.getClass().getName().equals(RESULT + "$Pass")
                || result instanceof Enum<?> e && e.name().equals("PASS");
    }

    private static Object constant(Object self, String name) {
        ClassLoader loader = self.getClass().getClassLoader();
        return CONSTANTS.computeIfAbsent(loader, l -> new ConcurrentHashMap<>()).computeIfAbsent(name, n -> {
            try {
                return Class.forName(RESULT, true, loader).getField(n).get(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("this Minecraft has no InteractionResult." + n, e);
            }
        });
    }

    private static Method method(Class<?> type, String name) throws NoSuchMethodException {
        Method method = type.getMethod(name);
        method.setAccessible(true);
        return method;
    }

    private static void warnOnce(Throwable e) {
        if (!warned) {
            warned = true;
            LOG.warn("an older mod's item use could not be read; it passes instead: {}",
                    Failures.describe(Failures.unwrap(e)));
        }
    }
}
