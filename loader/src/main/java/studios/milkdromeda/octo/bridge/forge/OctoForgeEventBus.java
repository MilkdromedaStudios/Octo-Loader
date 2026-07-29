package studios.milkdromeda.octo.bridge.forge;

import java.util.function.Consumer;

import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Forge's {@code IEventBus}, backed by {@link EventDispatcher}. */
public final class OctoForgeEventBus implements IEventBus {
    private final EventDispatcher dispatcher;

    OctoForgeEventBus(String modId) {
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
    public boolean post(Event event) {
        dispatcher.post(event);
        return event.isCancelable() && event.isCanceled();
    }

    public int listenerCount() {
        return dispatcher.size();
    }
}
