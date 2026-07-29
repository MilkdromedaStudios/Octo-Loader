package net.neoforged.bus.api;

import java.util.function.Consumer;

/** NeoForge's event bus, as Octo implements it. */
public interface IEventBus {
    void register(Object target);

    void unregister(Object target);

    <T extends Event> void addListener(Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer);

    <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer);

    // The rest of the family, where the mod also says whether it wants events
    // another listener has already cancelled. A mod reaching an overload the bus
    // does not declare does not get a compile error — it was compiled against
    // the real NeoForge — it gets its call quietly turned into a no-op, and its
    // listener never fires. That is what left JEI with no event handlers at all.

    <T extends Event> void addListener(boolean receiveCancelled, Consumer<T> consumer);

    <T extends Event> void addListener(boolean receiveCancelled, Class<T> eventType, Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Class<T> eventType,
            Consumer<T> consumer);

    <T extends Event> T post(T event);
}
