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

    public static net.minecraftforge.fml.ModContainer forgeContainerFor(String modId) {
        return FORGE_CONTAINERS.computeIfAbsent(modId, net.minecraftforge.fml.ModContainer::new);
    }

    public static OctoForgeEventBus commonForgeBus() {
        return COMMON_FORGE;
    }

    public static OctoNeoEventBus commonNeoBus() {
        return COMMON_NEO;
    }

    /** Test hook: forgets every registered listener. */
    public static void reset() {
        FORGE_BUSES.clear();
        NEO_BUSES.clear();
        NEO_CONTAINERS.clear();
        FORGE_CONTAINERS.clear();
        ACTIVE.remove();
    }
}
