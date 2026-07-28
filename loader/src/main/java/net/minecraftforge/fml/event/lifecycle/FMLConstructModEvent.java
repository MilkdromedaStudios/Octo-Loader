package net.minecraftforge.fml.event.lifecycle;

/** The mod object has just been constructed. */
public class FMLConstructModEvent extends ParallelDispatchEvent {
    public FMLConstructModEvent(String modId) {
        super(modId);
    }
}
