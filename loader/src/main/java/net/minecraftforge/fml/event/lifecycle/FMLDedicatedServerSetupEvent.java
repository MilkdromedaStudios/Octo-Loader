package net.minecraftforge.fml.event.lifecycle;

/** Dedicated-server-only setup. */
public class FMLDedicatedServerSetupEvent extends ParallelDispatchEvent {
    /** This event's bus within a mod's bus group, as Forge 66 mods subscribe to it. */
    @SuppressWarnings("rawtypes")
    public static net.minecraftforge.eventbus.api.bus.EventBus getBus(
            net.minecraftforge.eventbus.api.bus.BusGroup modBusGroup) {
        return net.minecraftforge.fml.event.IModBusEvent.getBus(modBusGroup, FMLDedicatedServerSetupEvent.class);
    }

    public FMLDedicatedServerSetupEvent(String modId) {
        super(modId);
    }
}
