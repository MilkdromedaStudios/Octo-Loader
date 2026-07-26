package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;

/** Every mod has finished loading. */
public class FMLLoadCompleteEvent extends Event {
    private final String modId;

    public FMLLoadCompleteEvent(String modId) {
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
