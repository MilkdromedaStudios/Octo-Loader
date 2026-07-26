package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;

/** Dedicated-server-only setup. */
public class FMLDedicatedServerSetupEvent extends Event {
    private final String modId;

    public FMLDedicatedServerSetupEvent(String modId) {
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
