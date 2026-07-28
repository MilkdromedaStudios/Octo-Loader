package net.neoforged.fml.loading;

import java.util.ArrayList;
import java.util.List;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;
import studios.milkdromeda.octo.bridge.forge.ModInfos;

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
        return ModInfos.loadedIds();
    }

    public boolean isLoaded(String modId) {
        return ModInfos.isPresent(modId);
    }

    /** @return the mod's own container, or {@code null} when it is not present */
    public ModContainer getModContainerById(String modId) {
        return isLoaded(modId) ? ForgeBuses.neoContainerFor(modId) : null;
    }

    /**
     * @return the file a mod was read from, or {@code null} when it is absent
     *
     * <p>Mods use a null answer here as "that mod is not installed" — Create's
     * whole compatibility layer is built on it — so this is a presence check
     * with a value attached, not a lookup that is expected to succeed.
     */
    public ModFileInfo getModFileById(String modId) {
        return ModFileInfo.of(modId);
    }

    public List<ModFileInfo> getModFiles() {
        List<ModFileInfo> out = new ArrayList<>();
        getModIds().forEach(id -> out.add(new ModFileInfo(id)));
        return out;
    }

    /** Every mod present, described the way NeoForge describes one. */
    public List<IModInfo> getMods() {
        List<IModInfo> out = new ArrayList<>();
        getModIds().forEach(id -> out.add(ModInfos.neo(id)));
        return out;
    }

    /** Nothing is rejected at this stage: Octo loads a mod and reports what it had to change. */
    public List<String> getErrors() {
        return List.of();
    }

    /** Nothing is rejected at this stage; see {@link #getErrors()}. */
    public List<String> getWarnings() {
        return List.of();
    }
}
