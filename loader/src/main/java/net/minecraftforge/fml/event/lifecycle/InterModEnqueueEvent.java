package net.minecraftforge.fml.event.lifecycle;

import net.minecraftforge.eventbus.api.Event;

/** Mods may send messages to each other. */
public class InterModEnqueueEvent extends Event {
    private final String modId;

    public InterModEnqueueEvent(String modId) {
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
