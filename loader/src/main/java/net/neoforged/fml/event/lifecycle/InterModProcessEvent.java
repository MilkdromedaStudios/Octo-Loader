package net.neoforged.fml.event.lifecycle;

/** Mods may read messages sent to them. */
public class InterModProcessEvent extends ParallelDispatchEvent {
    public InterModProcessEvent(String modId) {
        super(modId);
    }
}
