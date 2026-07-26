package studios.milkdromeda.octo.bridge.forge;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * The listener bookkeeping behind both event buses.
 *
 * <p>Forge and NeoForge declare their own {@code IEventBus} types with
 * incompatible signatures, so each gets its own implementation class, but the
 * work — finding {@code @SubscribeEvent} methods, ordering by priority,
 * delivering by parameter type — is the same and lives here.
 */
final class EventDispatcher {
    private static final OctoLog LOG = OctoLog.of(EventDispatcher.class);

    private final String modId;
    private final List<Listener> listeners = new ArrayList<>();

    EventDispatcher(String modId) {
        this.modId = modId;
    }

    private record Listener(Class<?> eventType, Object target, Method method, Consumer<Object> consumer, int priority) {
    }

    /** Scans an object (or class, for statics) for annotated listener methods. */
    void register(Object target, Class<? extends java.lang.annotation.Annotation> annotation) {
        Class<?> type = target instanceof Class<?> asClass ? asClass : target.getClass();
        Object receiver = target instanceof Class<?> ? null : target;
        int found = 0;

        for (Method method : type.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(annotation) || method.getParameterCount() != 1) {
                continue;
            }

            if (receiver == null && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                continue;
            }

            method.setAccessible(true);
            listeners.add(new Listener(method.getParameterTypes()[0], receiver, method, null,
                    priorityOf(method, annotation)));
            found++;
        }

        if (found == 0) {
            LOG.debug("{}: registered {} but found no listener methods", modId, type.getName());
        }
    }

    private static int priorityOf(Method method, Class<? extends java.lang.annotation.Annotation> annotation) {
        try {
            Object present = method.getAnnotation(annotation);
            Method accessor = annotation.getMethod("priority");
            Object priority = accessor.invoke(present);
            return priority instanceof Enum<?> value ? value.ordinal() : 2;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 2;
        }
    }

    void unregister(Object target) {
        Object receiver = target instanceof Class<?> ? null : target;
        listeners.removeIf(listener -> listener.target() == receiver
                || (receiver == null && listener.target() == null && listener.method().getDeclaringClass() == target));
    }

    /** @param eventType {@code null} when the caller could not name the type */
    void addListener(Class<?> eventType, Consumer<Object> consumer, int priority) {
        listeners.add(new Listener(eventType, null, null, consumer, priority));
    }

    void post(Object event) {
        List<Listener> ordered = new ArrayList<>(listeners);
        ordered.sort((left, right) -> Integer.compare(left.priority(), right.priority()));

        for (Listener listener : ordered) {
            if (listener.eventType() != null && !listener.eventType().isInstance(event)) {
                continue;
            }

            try {
                if (listener.consumer() != null) {
                    listener.consumer().accept(event);
                } else {
                    listener.method().invoke(listener.target(), event);
                }
            } catch (ClassCastException e) {
                // A listener added without naming its event type: generics are
                // erased, so the only way to know it does not want this event is
                // for its own cast to reject it.
                LOG.trace("{}: listener declined {}", modId, event.getClass().getSimpleName());
            } catch (ReflectiveOperationException | RuntimeException e) {
                Throwable cause = e instanceof java.lang.reflect.InvocationTargetException invocation
                        ? invocation.getCause() : e;

                if (cause instanceof ClassCastException) {
                    continue;
                }

                LOG.error("{}: listener for {} threw {}", modId, event.getClass().getSimpleName(), cause.toString());
            }
        }
    }

    int size() {
        return listeners.size();
    }
}
