package studios.milkdromeda.octo.bridge.forge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.Function;

import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.CancellableEventBus;
import net.minecraftforge.eventbus.api.bus.EventBus;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Working objects for the fields of classes Octo stands in for.
 *
 * <p>A stand-in for a missing class answers with defaults, and for most fields a
 * default is fine. For an event bus it is not: {@code NeoForge.EVENT_BUS} is the
 * first thing nearly every NeoForge mod touches in its constructor, and so is a
 * Forge 66 event's {@code BUS}. Null there is a mod that does not construct;
 * a bus that accepts listeners is a mod that loads. These are what the
 * generated stand-ins call to fill those fields.
 */
public final class LiveStandIns {
    private static final OctoLog LOG = OctoLog.of(LiveStandIns.class);

    private LiveStandIns() {
    }

    /** NeoForge's game bus, {@code NeoForge.EVENT_BUS}. */
    public static net.neoforged.bus.api.IEventBus neoBus() {
        return ForgeBuses.commonNeoBus();
    }

    /** Forge's game bus up to 1.21.5, {@code MinecraftForge.EVENT_BUS}. */
    public static net.minecraftforge.eventbus.api.IEventBus forgeBus() {
        return ForgeBuses.commonForgeBus();
    }

    /** Forge's default bus group from 1.21.6 on. */
    public static BusGroup busGroup() {
        return BusGroup.DEFAULT;
    }

    /**
     * An event's own bus, {@code SomeEvent.BUS}.
     *
     * <p>EventBus 7 only builds a bus for a class that declares what kind of
     * event it is, and a stand-in declares nothing. When it refuses, the field
     * still gets a bus that takes listeners and never calls them — which is
     * exactly what a bus for an event nobody will ever post should do.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static EventBus eventBus(Class<?> owner) {
        try {
            return EventBus.create((Class) owner);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.debug("{} cannot have a real EventBus ({}); using a silent one", owner.getName(), e.toString());
            return silent(EventBus.class);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static CancellableEventBus cancellableEventBus(Class<?> owner) {
        try {
            return CancellableEventBus.create((Class) owner);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.debug("{} cannot have a real CancellableEventBus ({}); using a silent one", owner.getName(),
                    e.toString());
            return silent(CancellableEventBus.class);
        }
    }

    /**
     * A Fabric API event for a callback Fabric API no longer has:
     * {@code HudRenderCallback.EVENT}, which 26.x replaced with
     * {@code HudElementRegistry}. Mods register on these in their initialisers,
     * so a null field was an initialiser that threw and a mod without its
     * client half; an event that takes the registration lets the rest of the
     * initialiser run. Made by Fabric API's own factory, so it is a real
     * {@code Event} whose invoker calls whatever was registered.
     *
     * @return the event, or {@code null} without Fabric API
     */
    public static Object fabricEvent(Class<?> owner) {
        try {
            Class<?> factory = Class.forName("net.fabricmc.fabric.api.event.EventFactory", true,
                    owner.getClassLoader());
            Function<Object[], Object> invokers = listeners -> invoker(owner, listeners);
            return factory.getMethod("createArrayBacked", Class.class, Function.class).invoke(null, owner, invokers);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.debug("no Fabric event for {}: {}", owner.getName(), e.toString());
            return null;
        }
    }

    private static final java.util.Map<String, Object> DETACHED = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * An empty registry under the name of one this game no longer has:
     * {@code BuiltInRegistries.FEATURE}, which 26.x replaced with a registry
     * of codecs. Create opens a DeferredRegister on it in a static
     * initialiser, and a null there was a mod that never constructed. The
     * registry is real and stays empty; nothing put in it reaches the game.
     * One per name, so every mod asking for it gets the same one.
     *
     * @param context a class of the game's, for its loader
     * @return the registry, or {@code null} if one could not be made
     */
    public static Object detachedRegistry(String name, Class<?> context) {
        return DETACHED.computeIfAbsent(name, ignored -> {
            try {
                ClassLoader loader = context.getClassLoader();
                Class<?> identifier = Class.forName("net.minecraft.resources.Identifier", true, loader);
                Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey", true, loader);
                Class<?> lifecycle = Class.forName("com.mojang.serialization.Lifecycle", true, loader);
                Class<?> registry = Class.forName("net.minecraft.core.MappedRegistry", true, loader);
                Object id = identifier.getMethod("withDefaultNamespace", String.class).invoke(null, name);
                Object key = resourceKey.getMethod("createRegistryKey", identifier).invoke(null, id);
                Object stable = lifecycle.getMethod("stable").invoke(null);
                return registry.getConstructor(resourceKey, lifecycle).newInstance(key, stable);
            } catch (Throwable e) {
                Failures.rethrowIfFatal(e);
                LOG.debug("no stand-in registry for {}: {}", name, e.toString());
                return null;
            }
        });
    }

    /** Calls every listener in turn, answering with the last one's result. */
    private static Object invoker(Class<?> owner, Object[] listeners) {
        if (!owner.isInterface()) {
            return null;
        }

        return Proxy.newProxyInstance(owner.getClassLoader(), new Class<?>[] { owner }, (proxy, method, arguments) -> {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method.getName(), arguments, owner);
            }

            Object result = null;

            for (Object listener : listeners) {
                try {
                    result = method.invoke(listener, arguments);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }

            return result != null ? result : defaultFor(method.getReturnType());
        });
    }

    /**
     * An object of an interface type that does nothing, or null for anything
     * else — what a call a translated mod makes into something this version no
     * longer has hands back, so the call after it does not fail on a null.
     */
    public static Object silentOrNull(Class<?> type) {
        if (type == null || !type.isInterface()) {
            return null;
        }

        try {
            return silent(type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** An implementation of {@code type} whose every method does nothing and returns a default. */
    @SuppressWarnings("unchecked")
    static <T> T silent(Class<T> type) {
        ClassLoader loader = type.getClassLoader() != null ? type.getClassLoader() : LiveStandIns.class.getClassLoader();
        return (T) Proxy.newProxyInstance(loader, new Class<?>[] { type },
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return objectMethod(proxy, method.getName(), arguments, type);
                    }

                    Class<?> returned = method.getReturnType();

                    // Builders and buses hand themselves back to be chained.
                    return returned.isInstance(proxy) ? proxy : defaultFor(returned);
                });
    }

    private static Object objectMethod(Object proxy, String name, Object[] arguments, Class<?> type) {
        return switch (name) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            default -> "octo silent " + type.getSimpleName();
        };
    }

    private static Object defaultFor(Class<?> returned) {
        if (returned == boolean.class) {
            return false;
        }

        if (returned.isPrimitive() && returned != void.class) {
            return returned == long.class ? 0L : returned == double.class ? 0d
                    : returned == float.class ? 0f : returned == char.class ? (char) 0
                    : returned == byte.class ? (byte) 0 : returned == short.class ? (short) 0 : 0;
        }

        return null;
    }
}
