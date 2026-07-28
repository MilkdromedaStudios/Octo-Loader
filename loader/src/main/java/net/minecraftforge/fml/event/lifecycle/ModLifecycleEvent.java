package net.minecraftforge.fml.event.lifecycle;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.event.IModBusEvent;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;

/** Forge's spelling of {@link net.neoforged.fml.event.lifecycle.ModLifecycleEvent}. */
public abstract class ModLifecycleEvent extends Event implements IModBusEvent {
    private final String modId;

    protected ModLifecycleEvent(String modId) {
        this.modId = modId;
    }

    public String getModId() {
        return modId;
    }

    /** The container of the mod this event was posted for. */
    public ModContainer getContainer() {
        return ForgeBuses.forgeContainerFor(modId);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + modId + ")";
    }
}
