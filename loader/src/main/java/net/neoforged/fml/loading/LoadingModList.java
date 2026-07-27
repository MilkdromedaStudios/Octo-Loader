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

    /**
     * @return the file a mod was read from, or {@code null} when it is absent
     *
     * <p>Mods use a null answer here as "that mod is not installed" — Create's
     * whole compatibility layer is built on it — so this is a presence check
     * with a value attached, not a lookup that is expected to succeed.
     */
    public net.neoforged.fml.loading.moddiscovery.ModFileInfo getModFileById(String modId) {
        return net.neoforged.fml.loading.moddiscovery.ModFileInfo.of(modId);
    }

    public List<net.neoforged.fml.loading.moddiscovery.ModFileInfo> getModFiles() {
        List<net.neoforged.fml.loading.moddiscovery.ModFileInfo> out = new ArrayList<>();
        getModIds().forEach(id -> out.add(new net.neoforged.fml.loading.moddiscovery.ModFileInfo(id)));
        return out;
    }

    /** Nothing is rejected at this stage: Octo loads a mod and reports what it had to change. */
    public List<String> getErrors() {
        return List.of();
    }
}
