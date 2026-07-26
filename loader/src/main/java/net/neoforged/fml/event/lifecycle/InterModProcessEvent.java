package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;

/** Mods may read messages sent to them. */
public class InterModProcessEvent extends Event {
    private final String modId;

    public InterModProcessEvent(String modId) {
        this.modId = modId;
    }

    public String getModId() {
        return modId;
    }

    /** Runs work after loading finishes; Octo runs it immediately and in order. */
    public void enqueueWork(Runnable work) {
        work.run();
    }
}
