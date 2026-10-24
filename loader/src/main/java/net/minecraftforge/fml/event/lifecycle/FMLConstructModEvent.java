package net.minecraftforge.fml.event.lifecycle;

/** The mod object has just been constructed. */
public class FMLConstructModEvent extends ParallelDispatchEvent {
    /** This event's bus within a mod's bus group, as Forge 66 mods subscribe to it. */
    @SuppressWarnings("rawtypes")
    public static net.minecraftforge.eventbus.api.bus.EventBus getBus(
            net.minecraftforge.eventbus.api.bus.BusGroup modBusGroup) {
        return net.minecraftforge.fml.event.IModBusEvent.getBus(modBusGroup, FMLConstructModEvent.class);
    }

    public FMLConstructModEvent(String modId) {
        super(modId);
    }
}
