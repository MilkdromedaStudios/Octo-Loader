package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;

/** Setup shared by both sides. */
public class FMLCommonSetupEvent extends Event {
    private final String modId;

    public FMLCommonSetupEvent(String modId) {
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
