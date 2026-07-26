package net.minecraftforge.fml;

import net.minecraftforge.eventbus.api.IEventBus;

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

    /** The bus the active mod's own events are posted on. */
    public IEventBus getModEventBus() {
        return ForgeBuses.forgeBusFor(ForgeBuses.activeModId());
    }
}
