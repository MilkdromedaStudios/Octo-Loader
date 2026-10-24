package net.minecraftforge.fml.event;

import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;

/**
 * Marks an event that goes on a mod's own bus rather than the game's.
 *
 * <p>Up to 1.21.5 this was an empty marker. From Forge 66 (1.21.6 onwards, and
 * so every Forge mod for 26.x) each mod has its own EventBus 7 {@link BusGroup}
 * and a mod-bus event reaches it through {@link #getBus}. This is the Java 17
 * shape; on Java 21 and later the loader jar serves the one in
 * {@code src/java21}, which is also an EventBus 7 event.
 */
public interface IModBusEvent {
    // Raw, because on Java 17 this is not yet an EventBus 7 event; the erased
    // descriptor is what mods link against, and it is the same either way.
    @SuppressWarnings({"unchecked", "rawtypes"})
    static EventBus getBus(BusGroup modEventBusGroup, Class<? extends IModBusEvent> eventClass) {
        return EventBus.create(modEventBusGroup, (Class) eventClass);
    }
}
