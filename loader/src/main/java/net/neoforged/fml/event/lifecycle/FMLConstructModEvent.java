package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;

/** The mod object has just been constructed. */
public class FMLConstructModEvent extends Event {
    private final String modId;

    public FMLConstructModEvent(String modId) {
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
