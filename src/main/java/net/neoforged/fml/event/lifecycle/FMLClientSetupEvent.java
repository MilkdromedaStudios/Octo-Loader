package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;

/**
 * Octo Loader shim: fired on the mod's bus during client initialization.
 */
public class FMLClientSetupEvent extends Event {
    public void enqueueWork(Runnable work) {
        work.run();
    }
}
