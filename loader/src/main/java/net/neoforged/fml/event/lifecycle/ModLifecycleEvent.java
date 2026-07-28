package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.IModBusEvent;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;

/**
 * One step of a mod's startup, posted on that mod's own bus.
 *
 * <p>The common superclass matters as much as the individual events do: mods
 * write a single handler taking {@code ModLifecycleEvent} and register it for
 * every phase, and a hierarchy of unrelated classes turns that into a listener
 * that is never called.
 */
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
        return ForgeBuses.neoContainerFor(modId);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + modId + ")";
    }
}
