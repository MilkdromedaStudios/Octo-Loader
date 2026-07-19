package studios.milkdromeda.octoloader.shims.neoforge;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Minimal working implementation of NeoForge's mod event bus.
 *
 * <p>Type-specific listeners are dispatched by event class; listeners added
 * without an explicit class (the common lambda form) are kept as wildcards and
 * offered every event, ignoring the ones whose type doesn't fit. Event volume
 * on the mod bus is tiny (lifecycle only for now), so this stays cheap while
 * avoiding fragile generic-parameter reflection on lambdas.
 */
public final class OctoEventBus implements IEventBus {
    private static final Logger LOGGER = LoggerFactory.getLogger("OctoLoader/NeoForgeBus");

    private record TypedListener(Class<?> type, Consumer<Event> consumer) {
    }

    private final String modId;
    private final List<TypedListener> typed = new ArrayList<>();
    private final List<Consumer<? extends Event>> wildcards = new ArrayList<>();

    public OctoEventBus(String modId) {
        this.modId = modId;
    }

    @Override
    public <T extends Event> void addListener(Consumer<T> listener) {
        wildcards.add(listener);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(Class<T> eventType, Consumer<T> listener) {
        typed.add(new TypedListener(eventType, (Consumer<Event>) listener));
    }

    @Override
    public void register(Object target) {
        boolean staticOnly = target instanceof Class<?>;
        Class<?> cls = staticOnly ? (Class<?>) target : target.getClass();
        for (Method method : cls.getMethods()) {
            if (!method.isAnnotationPresent(SubscribeEvent.class)) continue;
            if (staticOnly && !Modifier.isStatic(method.getModifiers())) continue;
            if (method.getParameterCount() != 1 || !Event.class.isAssignableFrom(method.getParameterTypes()[0])) {
                continue;
            }
            Object receiver = Modifier.isStatic(method.getModifiers()) ? null : target;
            typed.add(new TypedListener(method.getParameterTypes()[0], event -> {
                try {
                    method.invoke(receiver, event);
                } catch (ReflectiveOperationException e) {
                    LOGGER.error("[{}] @SubscribeEvent handler {} failed", modId, method, e);
                }
            }));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> T post(T event) {
        for (TypedListener listener : typed) {
            if (listener.type().isInstance(event)) {
                listener.consumer().accept(event);
            }
        }
        for (Consumer<? extends Event> wildcard : wildcards) {
            try {
                ((Consumer<Event>) wildcard).accept(event);
            } catch (ClassCastException ignored) {
                // Wildcard listener for a different event type.
            }
        }
        return event;
    }
}
