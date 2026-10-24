package net.neoforged.bus.api;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;

/**
 * NeoForge's factory for event buses.
 *
 * <p>{@code NeoForge.EVENT_BUS} is built with this, and so is every bus a mod
 * makes for its own API. The options tune NeoForge's own bus implementation and
 * mean nothing to Octo's, so they are accepted and the bus that comes out is the
 * one every other bus in the launch is.
 */
public final class BusBuilder {
    private String name = "custom";

    private BusBuilder() {
    }

    public static BusBuilder builder() {
        return new BusBuilder();
    }

    public BusBuilder setExceptionHandler(Object handler) {
        return this;
    }

    public BusBuilder startShutdown() {
        return this;
    }

    public BusBuilder checkTypesOnDispatch() {
        return this;
    }

    public BusBuilder markerType(Class<?> type) {
        name = type.getSimpleName();
        return this;
    }

    public BusBuilder classChecker(Object checker) {
        return this;
    }

    public BusBuilder allowPerPhasePost() {
        return this;
    }

    public BusBuilder useModLauncher() {
        return this;
    }

    public IEventBus build() {
        return ForgeBuses.neoBusFor("octo:" + name + ":" + System.identityHashCode(this));
    }
}
