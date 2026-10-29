package studios.milkdromeda.octo.bridge.forge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The event buses, one pair per mod, plus the mod currently being constructed.
 *
 * <p>Forge mods reach their bus through a static call inside their constructor
 * ({@code FMLJavaModLoadingContext.get().getModEventBus()}), so the loader has
 * to make "which mod is being constructed right now" answerable. That is what
 * the active-mod marker is for; it is set around construction and cleared after.
 */
public final class ForgeBuses {
    private static final Map<String, OctoForgeEventBus> FORGE_BUSES = new ConcurrentHashMap<>();
    private static final Map<String, OctoNeoEventBus> NEO_BUSES = new ConcurrentHashMap<>();
    private static final Map<String, net.neoforged.fml.ModContainer> NEO_CONTAINERS = new ConcurrentHashMap<>();
    private static final Map<String, net.minecraftforge.fml.ModContainer> FORGE_CONTAINERS = new ConcurrentHashMap<>();
    private static final Map<String, net.minecraftforge.eventbus.api.bus.BusGroup> FORGE_GROUPS =
            new ConcurrentHashMap<>();
    private static final Map<String, Object> FORGE_CONTEXTS = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> ACTIVE = new ThreadLocal<>();

    /** The bus every mod can post on, the one Forge calls the "Forge bus". */
    private static final OctoForgeEventBus COMMON_FORGE = new OctoForgeEventBus("minecraft");
    private static final OctoNeoEventBus COMMON_NEO = new OctoNeoEventBus("minecraft");

    private ForgeBuses() {
    }

    public static String activeModId() {
        String active = ACTIVE.get();
        return active == null ? "minecraft" : active;
    }

    public static void setActive(String modId) {
        if (modId == null) {
            ACTIVE.remove();
        } else {
            ACTIVE.set(modId);
        }
    }

    public static OctoForgeEventBus forgeBusFor(String modId) {
        return FORGE_BUSES.computeIfAbsent(modId, OctoForgeEventBus::new);
    }

    public static OctoNeoEventBus neoBusFor(String modId) {
        return NEO_BUSES.computeIfAbsent(modId, OctoNeoEventBus::new);
    }

    /**
     * The container a NeoForge mod is handed at construction and reaches later
     * through {@code ModLoadingContext}. One per mod, because a mod registers
     * its config and its extension points on it and expects to read them back.
     */
    public static net.neoforged.fml.ModContainer neoContainerFor(String modId) {
        return NEO_CONTAINERS.computeIfAbsent(modId, net.neoforged.fml.ModContainer::new);
    }

    /**
     * The mod's own EventBus 7 bus group, as Forge 66 hands it out.
     *
     * <p>Named after the mod, as Forge's own are; EventBus refuses two groups
     * with the same name, so one is made per mod and reused.
     */
    public static net.minecraftforge.eventbus.api.bus.BusGroup forgeBusGroupFor(String modId) {
        return FORGE_GROUPS.computeIfAbsent(modId, id -> {
            try {
                return net.minecraftforge.eventbus.api.bus.BusGroup.create("octo:" + id + ":" + System.nanoTime());
            } catch (RuntimeException e) {
                return net.minecraftforge.eventbus.api.bus.BusGroup.DEFAULT;
            }
        });
    }

    @SuppressWarnings("unchecked")
    public static <T> T forgeContextFor(String modId, java.util.function.Function<String, T> factory) {
        return (T) FORGE_CONTEXTS.computeIfAbsent(modId, factory::apply);
    }

    public static net.minecraftforge.fml.ModContainer forgeContainerFor(String modId) {
        return FORGE_CONTAINERS.computeIfAbsent(modId, net.minecraftforge.fml.ModContainer::new);
    }

    public static OctoForgeEventBus commonForgeBus() {
        return COMMON_FORGE;
    }

    public static OctoNeoEventBus commonNeoBus() {
        return COMMON_NEO;
    }

    /**
     * Posts an event the loaders' patched game fires on every mod's bus, as
     * their client fires its model events. A NeoForge event reaches NeoForge
     * mods; anything else reaches Forge mods, and the event's own EventBus 7
     * bus, where Forge 66 mods listen.
     */
    public static void postToEveryMod(Object event) {
        if (event instanceof net.neoforged.bus.api.Event) {
            for (OctoNeoEventBus bus : NEO_BUSES.values()) {
                bus.postAny(event);
            }

            return;
        }

        for (OctoForgeEventBus bus : FORGE_BUSES.values()) {
            bus.postAny(event);
        }

        try {
            java.lang.reflect.Field field = event.getClass().getField("BUS");

            if (field.get(null) instanceof net.minecraftforge.eventbus.api.bus.EventBus<?> bus
                    && event instanceof net.minecraftforge.eventbus.internal.Event forge && bus.hasListeners()) {
                postTo(bus, forge);
            }
        } catch (NoSuchFieldException | IllegalAccessException | RuntimeException e) {
            // Not an EventBus 7 event; the mod buses above are all it has.
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void postTo(net.minecraftforge.eventbus.api.bus.EventBus bus,
            net.minecraftforge.eventbus.internal.Event event) {
        bus.post(event);
    }

    /** Test hook: forgets every registered listener. */
    public static void reset() {
        FORGE_BUSES.clear();
        NEO_BUSES.clear();
        NEO_CONTAINERS.clear();
        FORGE_CONTAINERS.clear();
        FORGE_GROUPS.clear();
        FORGE_CONTEXTS.clear();
        ACTIVE.remove();
    }
}
