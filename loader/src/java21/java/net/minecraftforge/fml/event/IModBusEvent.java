package net.minecraftforge.fml.event;

import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.eventbus.api.bus.EventBus;
import net.minecraftforge.eventbus.api.event.InheritableEvent;

/**
 * The Java 21 shape of {@code IModBusEvent}, served from
 * {@code META-INF/versions/21} of the loader jar.
 *
 * <p>Forge 66 makes this an EventBus 7 {@link InheritableEvent}, and EventBus 7
 * will only build a bus for an event that is one. EventBus 7 is Java 21 class
 * files, though, and the loader has to start on Java 17 for the versions that
 * run there — where a mod-bus marker extending a Java 21 interface would stop
 * every older Forge mod's lifecycle event from loading at all. A multi-release
 * jar gives each JVM the version it can use.
 */
public interface IModBusEvent extends InheritableEvent {
    static <T extends IModBusEvent> EventBus<T> getBus(BusGroup modEventBusGroup, Class<T> eventClass) {
        return EventBus.create(modEventBusGroup, eventClass);
    }
}
