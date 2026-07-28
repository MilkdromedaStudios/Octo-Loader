package net.neoforged.fml.event.lifecycle;

/** Every mod has finished loading. */
public class FMLLoadCompleteEvent extends ParallelDispatchEvent {
    public FMLLoadCompleteEvent(String modId) {
        super(modId);
    }
}
