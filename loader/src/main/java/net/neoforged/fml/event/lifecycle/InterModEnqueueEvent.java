package net.neoforged.fml.event.lifecycle;

/** Mods may send messages to each other. */
public class InterModEnqueueEvent extends ParallelDispatchEvent {
    public InterModEnqueueEvent(String modId) {
        super(modId);
    }
}
