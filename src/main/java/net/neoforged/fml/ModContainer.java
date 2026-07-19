package net.neoforged.fml;

import net.neoforged.bus.api.IEventBus;

/**
 * Octo Loader shim of NeoForge's per-mod container, backed by the translated
 * mod's Fabric metadata.
 */
public class ModContainer {
    private final String modId;
    private final IEventBus eventBus;

    public ModContainer(String modId, IEventBus eventBus) {
        this.modId = modId;
        this.eventBus = eventBus;
    }

    public String getModId() {
        return modId;
    }

    public IEventBus getEventBus() {
        return eventBus;
    }
}
