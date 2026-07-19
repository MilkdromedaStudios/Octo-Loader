package net.neoforged.bus.api;

import java.util.function.Consumer;

/**
 * Octo Loader shim of NeoForge's event bus interface — the subset most mod
 * entry constructors use to attach lifecycle listeners.
 */
public interface IEventBus {
    <T extends Event> void addListener(Consumer<T> listener);

    <T extends Event> void addListener(Class<T> eventType, Consumer<T> listener);

    /** Registers {@code @SubscribeEvent} methods of the given listener object or class. */
    void register(Object target);

    <T extends Event> T post(T event);
}
