package net.neoforged.fml.loading.moddiscovery;

import java.util.List;

import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

import studios.milkdromeda.octo.bridge.forge.ModInfos;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/**
 * What NeoForge knows about one mod file before the game exists.
 *
 * <p>Mods reach for this through {@code LoadingModList.get().getModFileById(id)},
 * almost always to answer "is that other mod here" during a static initialiser
 * or a mixin configuration plugin — Create's {@code Mods} enum does both, which
 * is why an unimplemented lookup there stopped the entire launch rather than one
 * compatibility branch.
 *
 * <p>Octo answers from its own runtime. The parts of NeoForge's version that
 * describe its module and secure-jar machinery have no counterpart here and are
 * not pretended at; what a mod actually asks — the id, the version, the file it
 * came from — is real.
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

    /**
     * The mods declared in this file.
     *
     * <p>These are {@code IModInfo}, not ids: mods read the version straight off
     * the first element — {@code getMods().get(0).getVersion()} is Create's
     * idiom — and a list of strings there is exactly the
     * {@code NoSuchMethodError} on {@code IModInfo.getVersion()} that took
     * Create's compatibility layer down with it.
     */
    @Override
    public List<IModInfo> getMods() {
        return List.of(ModInfos.neo(modId));
    }

    public String getModId() {
        return modId;
    }

    /** The archive this mod was read from. */
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
        return OctoRuntime.VERSION;
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
