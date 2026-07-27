package net.neoforged.fml.loading;

import java.util.ArrayList;
import java.util.List;

import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * The mod list as it stood during loading, answered from Octo's runtime.
 *
 * <p>Mods read this to find out what else is present before the game exists —
 * Flywheel asks for it in its constructor, which is as early as it gets.
 */
public final class LoadingModList {
    private static final LoadingModList INSTANCE = new LoadingModList();

    private LoadingModList() {
    }

    public static LoadingModList get() {
        return INSTANCE;
    }

    public List<String> getModIds() {
        List<String> out = new ArrayList<>();

        if (OctoRuntime.isRunning()) {
            OctoRuntime.get().loadedMods().forEach(mod -> out.add(mod.id()));
        }

        return out;
    }

    public boolean isLoaded(String modId) {
        return OctoRuntime.isRunning() && OctoRuntime.get().isModLoaded(modId);
    }

    /** @return the mod's own container, or {@code null} when it is not present */
    public net.neoforged.fml.ModContainer getModContainerById(String modId) {
        if (!OctoRuntime.isRunning()) {
            return null;
        }

        LoadedMod mod = OctoRuntime.get().mod(modId).orElse(null);
        return mod == null ? null : studios.milkdromeda.octo.bridge.forge.ForgeBuses.neoContainerFor(mod.id());
    }

    /** Nothing is rejected at this stage: Octo loads a mod and reports what it had to change. */
    public List<String> getErrors() {
        return List.of();
    }
}
