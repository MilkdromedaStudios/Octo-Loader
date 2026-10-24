package net.minecraftforge.fml.common.eventhandler;

/**
 * Forge's event bus from 1.8 to 1.12, {@code MinecraftForge.EVENT_BUS}.
 *
 * <p>The events a 1.12 mod listens for — 1.12's tick, player and render
 * events — are not the events this game posts, so nothing registered here is
 * ever called. What it buys is a mod that registers its handlers in its
 * constructor and goes on loading, where a null bus stopped it there.
 */
public class EventBus {
    public void register(Object target) {
    }

    public void unregister(Object target) {
    }

    /** @return whether a listener cancelled the event, which with none listening is never */
    public boolean post(Object event) {
        return false;
    }
}
