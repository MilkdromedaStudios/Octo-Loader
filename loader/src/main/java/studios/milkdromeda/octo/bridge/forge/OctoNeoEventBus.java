package studios.milkdromeda.octo.bridge.forge;

import java.util.function.Consumer;

import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;

/** NeoForge's {@code IEventBus}, backed by {@link EventDispatcher}. */
public final class OctoNeoEventBus implements IEventBus {
    private final EventDispatcher dispatcher;

    OctoNeoEventBus(String modId) {
        this.dispatcher = new EventDispatcher(modId);
    }

    @Override
    public void register(Object target) {
        dispatcher.register(target, SubscribeEvent.class);
    }

    @Override
    public void unregister(Object target) {
        dispatcher.unregister(target);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(Consumer<T> consumer) {
        dispatcher.addListener(null, event -> ((Consumer<Object>) (Consumer<?>) consumer).accept(event),
                EventPriority.NORMAL.ordinal());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(EventPriority priority, Consumer<T> consumer) {
        dispatcher.addListener(null, event -> ((Consumer<Object>) (Consumer<?>) consumer).accept(event), priority.ordinal());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer) {
        dispatcher.addListener(eventType, event -> ((Consumer<Object>) (Consumer<?>) consumer).accept(event),
                EventPriority.NORMAL.ordinal());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(boolean receiveCancelled, Consumer<T> consumer) {
        dispatcher.addListener(null, event -> ((Consumer<Object>) (Consumer<?>) consumer).accept(event),
                EventPriority.NORMAL.ordinal(), receiveCancelled);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(boolean receiveCancelled, Class<T> eventType, Consumer<T> consumer) {
        dispatcher.addListener(eventType, event -> ((Consumer<Object>) (Consumer<?>) consumer).accept(event),
                EventPriority.NORMAL.ordinal(), receiveCancelled);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Consumer<T> consumer) {
        dispatcher.addListener(null, event -> ((Consumer<Object>) (Consumer<?>) consumer).accept(event),
                priority.ordinal(), receiveCancelled);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(EventPriority priority, boolean receiveCancelled, Class<T> eventType,
            Consumer<T> consumer) {
        dispatcher.addListener(eventType, event -> ((Consumer<Object>) (Consumer<?>) consumer).accept(event),
                priority.ordinal(), receiveCancelled);
    }

    @Override
    public <T extends Event> T post(T event) {
        dispatcher.post(event);
        return event;
    }

    public int listenerCount() {
        return dispatcher.size();
    }
}
