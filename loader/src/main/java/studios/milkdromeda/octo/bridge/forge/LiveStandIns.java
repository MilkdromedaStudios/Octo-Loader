package studios.milkdromeda.octo.bridge.forge;

import java.lang.reflect.Proxy;

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

    /** An implementation of {@code type} whose every method does nothing and returns a default. */
    @SuppressWarnings("unchecked")
    static <T> T silent(Class<T> type) {
        return (T) Proxy.newProxyInstance(LiveStandIns.class.getClassLoader(), new Class<?>[] { type },
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == arguments[0];
                            default -> "octo silent " + type.getSimpleName();
                        };
                    }

                    Class<?> returned = method.getReturnType();

                    if (returned == boolean.class) {
                        return false;
                    }

                    if (returned.isPrimitive() && returned != void.class) {
                        return returned == long.class ? 0L : returned == double.class ? 0d
                                : returned == float.class ? 0f : returned == char.class ? (char) 0
                                : returned == byte.class ? (byte) 0 : returned == short.class ? (short) 0 : 0;
                    }

                    // Builders and buses hand themselves back to be chained.
                    return returned.isInstance(proxy) ? proxy : null;
                });
    }
}
