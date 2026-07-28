package net.neoforged.fml.event.lifecycle;

/** Setup shared by both sides. */
public class FMLCommonSetupEvent extends ParallelDispatchEvent {
    public FMLCommonSetupEvent(String modId) {
        super(modId);
    }
}
