package net.minecraftforge.fml.event.lifecycle;

/** Mods may send messages to each other. */
public class InterModEnqueueEvent extends ParallelDispatchEvent {
    /** This event's bus within a mod's bus group, as Forge 66 mods subscribe to it. */
    @SuppressWarnings("rawtypes")
    public static net.minecraftforge.eventbus.api.bus.EventBus getBus(
            net.minecraftforge.eventbus.api.bus.BusGroup modBusGroup) {
        return net.minecraftforge.fml.event.IModBusEvent.getBus(modBusGroup, InterModEnqueueEvent.class);
    }

    public InterModEnqueueEvent(String modId) {
        super(modId);
    }
}
