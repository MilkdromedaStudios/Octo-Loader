package studios.milkdromeda.octo.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * What is being registered right now, for the blocks and items that need to
 * know their own id while they are built.
 *
 * <p>Since 1.21.2 a block or an item takes its id from its properties, in its
 * constructor: {@code new Block(properties)} with no {@code setId} called on
 * them fails. Every mod written before then builds its blocks that way, so
 * none of them could register a single one — Create's 1,531 registrations
 * all failed. The registration they are part of knows the id, so it is
 * recorded here while each entry is built, and a block or item whose
 * properties lack one is given it. One built outside any registration — a
 * Fabric mod builds its block before it registers it — gets a placeholder,
 * which is enough to exist.
 */
public final class RegistrationContext {
    private static final OctoLog LOG = OctoLog.of(RegistrationContext.class);
    private static final ThreadLocal<Object[]> CURRENT = new ThreadLocal<>();
    private static final AtomicInteger UNNAMED = new AtomicInteger();

    private RegistrationContext() {
    }

    private static final ThreadLocal<java.util.ArrayDeque<Object[]>> ENTERED =
            ThreadLocal.withInitial(java.util.ArrayDeque::new);

    /**
     * Marks the start of building one entry, for a registration library that
     * builds its entries itself: Registrate's {@code Registration.register}.
     */
    public static void enter(Object registryKey, Object id) {
        ENTERED.get().push(CURRENT.get() == null ? new Object[0] : CURRENT.get());
        CURRENT.set(new Object[] { registryKey, id });
    }

    public static void exit() {
        java.util.ArrayDeque<Object[]> stack = ENTERED.get();
        Object[] previous = stack.isEmpty() ? new Object[0] : stack.pop();
        CURRENT.set(previous.length == 0 ? null : previous);
    }

    /** Forgets whatever an entry that threw left behind. */
    public static void clear() {
        ENTERED.get().clear();
        CURRENT.remove();
    }

    /**
     * Called by {@code RegisterEvent.register(key, id, supplier)} on its
     * supplier: builds the entry knowing what it is, and says which one it
     * was when it cannot be built.
     */
    public static Supplier<?> wrap(Supplier<?> supplier, Object registryKey, Object id) {
        if (supplier == null) {
            return null;
        }

        return () -> {
            Object[] previous = CURRENT.get();
            CURRENT.set(new Object[] { registryKey, id });

            try {
                return supplier.get();
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.warn("{} {} could not be made, so it is not registered: {}", path(registryKey), id,
                        Failures.describe(Failures.unwrap(e)));
                OctoLog.detail(e);
                throw e;
            } finally {
                CURRENT.set(previous);
            }
        };
    }

    /**
     * Called at the start of the {@code BlockBehaviour} and {@code Item}
     * constructors with their properties: gives them an id when they have none.
     *
     * @param registry {@code block} or {@code item}
     */
    public static void ensureId(Object properties, String registry) {
        if (properties == null) {
            return;
        }

        try {
            Field id = properties.getClass().getDeclaredField("id");
            id.setAccessible(true);

            if (id.get(properties) != null) {
                return;
            }

            ClassLoader loader = properties.getClass().getClassLoader();
            Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey", false, loader);
            Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", false, loader);
            Method create = resourceKey.getMethod("create", resourceKey, identifier);
            Object[] current = CURRENT.get();
            Object key;

            if (current != null && registry.equals(path(current[0]))) {
                key = create.invoke(null, current[0], current[1]);
            } else {
                Object registryKey = Class.forName("net.minecraft.core.registries.Registries", true, loader)
                        .getField(registry.toUpperCase(java.util.Locale.ROOT)).get(null);
                Object placeholder = identifier.getMethod("fromNamespaceAndPath", String.class, String.class)
                        .invoke(null, "octo", "unnamed_" + registry + "_" + UNNAMED.incrementAndGet());
                key = create.invoke(null, registryKey, placeholder);
            }

            properties.getClass().getMethod("setId", resourceKey).invoke(properties, key);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.debug("could not give a {} its id: {}", registry, e.toString());
        }
    }

    /** {@code block} for the key of the block registry. */
    private static String path(Object registryKey) {
        try {
            Object id = registryKey.getClass().getMethod("identifier").invoke(registryKey);
            return (String) id.getClass().getMethod("getPath").invoke(id);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            return String.valueOf(registryKey);
        }
    }
}
