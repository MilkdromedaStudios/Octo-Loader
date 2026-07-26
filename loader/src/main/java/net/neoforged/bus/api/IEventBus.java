package net.neoforged.bus.api;

import java.util.function.Consumer;

/** NeoForge's event bus, as Octo implements it. */
public interface IEventBus {
    void register(Object target);

    void unregister(Object target);

    <T extends Event> void addListener(Consumer<T> consumer);

    <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer);

    <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer);

    <T extends Event> T post(T event);
}
