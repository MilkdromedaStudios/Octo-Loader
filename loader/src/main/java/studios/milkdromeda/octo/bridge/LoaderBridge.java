package studios.milkdromeda.octo.bridge;

import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.runtime.Lifecycle;
import studios.milkdromeda.octo.runtime.LoadedMod;

/**
 * Runs mods of one ecosystem.
 *
 * <p>A bridge owns the two things that differ between loaders: how a mod object
 * comes into existence, and how it is told that the game has reached a point in
 * startup. Everything else — discovery, resolution, transformation, the mod
 * list — is shared, which is why mods from four ecosystems can be in one run.
 */
public interface LoaderBridge {
    String name();

    boolean supports(ModFormat format);

    /** Creates the mod's objects and registers its entrypoints. */
    void construct(LoadedMod mod, ClassLoader classLoader) throws Exception;

    /** Tells the mod the game has reached {@code phase}. */
    void dispatch(Lifecycle phase, LoadedMod mod) throws Exception;
}
