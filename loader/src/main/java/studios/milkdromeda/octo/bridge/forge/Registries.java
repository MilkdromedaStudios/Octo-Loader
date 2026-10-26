package studios.milkdromeda.octo.bridge.forge;

import java.util.Set;

import studios.milkdromeda.octo.util.Failures;

/** The game's registries, as NeoForge's registration needs them answered. */
public final class Registries {
    /**
     * The vanilla registries whose entries make their own holders as they are
     * constructed: a block, an item, a fluid, an entity type or a game event
     * created after a freeze needs the map for those back.
     */
    private static final Set<String> INTRUSIVE = Set.of("block", "item", "fluid", "entity_type", "game_event");

    private Registries() {
    }

    /** Called from the {@code unfreeze} added to {@code MappedRegistry}. */
    public static boolean hasIntrusiveHolders(Object registry) {
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
