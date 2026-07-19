package net.neoforged.fml.event.lifecycle;

import net.neoforged.bus.api.Event;

/**
 * Octo Loader shim: fired once on the mod's bus after construction, on both
 * sides. Work enqueued here runs immediately — Octo initializes mods during
 * Fabric's serial init phase, which is already thread-safe.
 */
public class FMLCommonSetupEvent extends Event {
    public void enqueueWork(Runnable work) {
        work.run();
    }
}
