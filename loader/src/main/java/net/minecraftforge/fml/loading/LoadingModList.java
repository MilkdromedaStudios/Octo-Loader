package net.minecraftforge.fml.loading;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;

import studios.milkdromeda.octo.bridge.forge.ForgeBuses;
import studios.milkdromeda.octo.bridge.forge.ModInfos;

/**
 * Forge's spelling of {@link net.neoforged.fml.loading.LoadingModList}.
 *
 * <p>Create and Flywheel each ship a Forge build alongside their NeoForge one
 * and reach this class from the same place — a mixin configuration plugin, or
 * an enum's constructor — so leaving the Forge spelling out meant the Forge
 * builds of exactly the mods this exists for still could not start.
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
        return isLoaded(modId) ? ForgeBuses.forgeContainerFor(modId) : null;
    }

    /** @return the file a mod was read from, or {@code null} when it is absent */
    public ModFileInfo getModFileById(String modId) {
        return ModFileInfo.of(modId);
    }

    public List<ModFileInfo> getModFiles() {
        List<ModFileInfo> out = new ArrayList<>();
        getModIds().forEach(id -> out.add(new ModFileInfo(id)));
        return out;
    }

    /** Every mod present, described the way Forge describes one. */
    public List<IModInfo> getMods() {
        List<IModInfo> out = new ArrayList<>();
        getModIds().forEach(id -> out.add(ModInfos.forge(id)));
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
