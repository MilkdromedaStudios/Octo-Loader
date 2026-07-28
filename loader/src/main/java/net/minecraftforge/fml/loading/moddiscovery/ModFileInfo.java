package net.minecraftforge.fml.loading.moddiscovery;

import java.util.List;

import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;

import studios.milkdromeda.octo.bridge.forge.ModInfos;

/**
 * Forge's spelling of {@link net.neoforged.fml.loading.moddiscovery.ModFileInfo}.
 *
 * <p>Create, Flywheel and Ponder each ship a Forge build and a NeoForge build
 * that differ in little except this package name, so the Forge one has to be
 * answerable too or half the mods this change exists for still do not load.
 */
public final class ModFileInfo implements IModFileInfo {
    private final String modId;

    public ModFileInfo(String modId) {
        this.modId = modId;
    }

    /** @return the info for a loaded mod, or {@code null} when it is not present */
    public static ModFileInfo of(String modId) {
        return ModInfos.isPresent(modId) ? new ModFileInfo(modId) : null;
    }

    @Override
    public String moduleName() {
        return modId;
    }

    @Override
    public String versionString() {
        return ModInfos.versionOf(modId);
    }

    @Override
    public List<IModInfo> getMods() {
        return List.of(ModInfos.forge(modId));
    }

    public String getModId() {
        return modId;
    }

    @Override
    public String getFileName() {
        return ModInfos.fileNameOf(modId);
    }

    @Override
    public String getLicense() {
        return ModInfos.licenseOf(modId);
    }

    @Override
    public List<String> usesServices() {
        return List.of();
    }

    @Override
    public String getModLoader() {
        return ModInfos.modLoaderOf(modId);
    }

    @Override
    public String getModLoaderVersion() {
        return studios.milkdromeda.octo.runtime.OctoRuntime.VERSION;
    }

    @Override
    public boolean showAsResourcePack() {
        return false;
    }

    @Override
    public boolean showAsDataPack() {
        return false;
    }

    @Override
    public String toString() {
        return "Mod File: " + getFileName();
    }
}
