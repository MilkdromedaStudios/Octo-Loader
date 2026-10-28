package studios.milkdromeda.octo.transform;

import java.util.Map;
import java.util.Set;

/**
 * Lets one entry of the game's own per-entry work fail without taking the
 * rest with it, where a translated mod's entry is among them.
 *
 * <p>Since 26.1 the game builds every item's default components while it
 * loads data, one initialiser per item, in one loop. An item from a mod
 * written earlier hands it what a removed member answered — Create add-ons
 * make a music disc with {@code jukeboxPlayable(null)} — and the one failing
 * initialiser stopped the data load, and the server. Each such method is
 * guarded as a callback is ({@link CallbackGuard}): reported once, then
 * skipped; that item has the components it had before.
 */
public final class GameMethodGuard implements Transformer {
    private static final Map<String, Set<String>> GUARDED = Map.of(
            "net/minecraft/core/component/DataComponentInitializers$InitializerEntry",
            Set.of("run(Lnet/minecraft/core/component/DataComponentMap$Builder;"
                    + "Lnet/minecraft/core/HolderLookup$Provider;)V"),
            // And the step that looks each item up afterwards: an item a mod
            // made and never registered — Octo names one that is made without
            // an id, so it can be made at all — has components waiting under
            // a name the registry does not have, and the lookup threw.
            "net/minecraft/core/component/DataComponentInitializers",
            Set.of("lambda$createInitializerForRegistry$0(Lnet/minecraft/core/HolderLookup$RegistryLookup;"
                    + "Ljava/util/List;Ljava/util/Set;Lnet/minecraft/resources/ResourceKey;"
                    + "Lnet/minecraft/core/component/DataComponentMap$Builder;)V"));

    @Override
    public String name() {
        return "game-method-guard";
    }

    @Override
    public boolean handles(String className, TransformContext context) {
        return GUARDED.containsKey(className);
    }

    @Override
    public byte[] transform(String className, byte[] bytes, TransformContext context) {
        return CallbackGuard.guardMethods(bytes, className, GUARDED.get(className));
    }
}
