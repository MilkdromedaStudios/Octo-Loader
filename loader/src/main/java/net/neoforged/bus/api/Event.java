package net.neoforged.bus.api;

/** Base class for everything posted on a NeoForge event bus. */
public abstract class Event {
    private boolean canceled;

    public boolean isCancelable() {
        return false;
    }

    public boolean isCanceled() {
        return canceled;
    }

    public void setCanceled(boolean cancel) {
        this.canceled = cancel;
    }
}
