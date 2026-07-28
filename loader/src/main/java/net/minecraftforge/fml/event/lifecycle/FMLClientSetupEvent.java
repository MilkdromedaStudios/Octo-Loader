package net.minecraftforge.fml.event.lifecycle;

/** Client-only setup. */
public class FMLClientSetupEvent extends ParallelDispatchEvent {
    public FMLClientSetupEvent(String modId) {
        super(modId);
    }
}
