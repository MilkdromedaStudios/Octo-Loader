package net.minecraftforge.fml.event.lifecycle;

/** Dedicated-server-only setup. */
public class FMLDedicatedServerSetupEvent extends ParallelDispatchEvent {
    public FMLDedicatedServerSetupEvent(String modId) {
        super(modId);
    }
}
