package studios.milkdromeda.octo.bridge.forge;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import studios.milkdromeda.octo.util.Failures;

/** The game's registries, as NeoForge's registration needs them answered. */
public final class Registries {
    /**
     * The vanilla registries whose entries make their own holders as they are
     * constructed: a block, an item, a fluid, an entity type or a game event
     * created after a freeze needs the map for those back.
     */
    private static final Set<String> INTRUSIVE = Set.of("block", "item", "fluid", "entity_type", "game_event");

    /** Every registry built to give intrusive holders, vanilla's or a mod's. */
    private static final Set<Object> BUILT_INTRUSIVE = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    private Registries() {
    }

    /** Called at the end of {@code MappedRegistry}'s constructor that says whether it gives intrusive holders. */
    public static void built(Object registry, boolean intrusive) {
        if (intrusive && registry != null) {
            BUILT_INTRUSIVE.add(registry);
        }
    }

    /** Called from the {@code unfreeze} added to {@code MappedRegistry}. */
    public static boolean hasIntrusiveHolders(Object registry) {
        if (BUILT_INTRUSIVE.contains(registry)) {
            return true;
        }

        try {
            Object key = registry.getClass().getMethod("key").invoke(registry);
            Object id = key.getClass().getMethod("identifier").invoke(key);
            String namespace = (String) id.getClass().getMethod("getNamespace").invoke(id);
            String path = (String) id.getClass().getMethod("getPath").invoke(id);
            return namespace.equals("minecraft") && INTRUSIVE.contains(path);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            return false;
        }
    }
}
