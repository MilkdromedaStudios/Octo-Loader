package net.neoforged.fml;

import net.neoforged.bus.api.IEventBus;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;

/** The mod currently being constructed. */
public final class ModLoadingContext {
    private static final ModLoadingContext INSTANCE = new ModLoadingContext();

    private ModLoadingContext() {
    }

    public static ModLoadingContext get() {
        return INSTANCE;
    }

    public String getActiveNamespace() {
        return ForgeBuses.activeModId();
    }

    public IEventBus getModEventBus() {
        return ForgeBuses.neoBusFor(ForgeBuses.activeModId());
    }
}
