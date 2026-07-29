package net.minecraftforge.eventbus.api;

import java.util.function.Consumer;

/** Forge's event bus, as Octo implements it. */
public interface IEventBus {
    void register(Object target);

    void unregister(Object target);

    <T extends Event> void addListener(Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer);

    <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer);

    // The rest of the family, where the mod also says whether it wants events
    // another listener has already cancelled. An overload the bus does not
    // declare costs the mod its listener silently: it was compiled against the
    // real Forge, so the call is there, and it becomes a no-op.

    <T extends Event> void addListener(boolean receiveCancelled, Consumer<T> consumer);

    <T extends Event> void addListener(boolean receiveCancelled, Class<T> eventType, Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Class<T> eventType,
            Consumer<T> consumer);

    boolean post(Event event);
}
