package studios.milkdromeda.octo.compat.fabric;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import studios.milkdromeda.octo.runtime.LoadedMod;

/** Pairs an entrypoint instance with the mod it came from. */
public final class OctoEntrypointContainer<T> implements EntrypointContainer<T> {
    private final T entrypoint;
    private final LoadedMod mod;

    public OctoEntrypointContainer(T entrypoint, LoadedMod mod) {
        this.entrypoint = entrypoint;
        this.mod = mod;
    }

    @Override
    public T getEntrypoint() {
        return entrypoint;
    }

    @Override
    public ModContainer getProvider() {
        return FabricModContainer.of(mod);
    }
}
