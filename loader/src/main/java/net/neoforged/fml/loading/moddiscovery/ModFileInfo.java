package net.neoforged.fml.loading.moddiscovery;

import java.util.List;
import java.util.Optional;

import studios.milkdromeda.octo.runtime.LoadedMod;
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
public final class ModFileInfo {
    private final String modId;

    public ModFileInfo(String modId) {
        this.modId = modId;
    }

    /** @return the info for a loaded mod, or {@code null} when it is not present */
    public static ModFileInfo of(String modId) {
        if (!OctoRuntime.isRunning() || !OctoRuntime.get().isModLoaded(modId)) {
            return null;
        }

        return new ModFileInfo(modId);
    }

    public String moduleName() {
        return modId;
    }

    public String versionString() {
        return mod().map(loaded -> loaded.metadata().version().raw()).orElse("0.0.0");
    }

    public List<String> getMods() {
        return List.of(modId);
    }

    public String getModId() {
        return modId;
    }

    /** The archive this mod was read from. */
    public String getFileName() {
        return mod().map(loaded -> loaded.effectivePath().getFileName().toString()).orElse(modId);
    }

    public String getLicense() {
        return mod().map(loaded -> loaded.metadata().license()).orElse("");
    }

    public List<String> usesServices() {
        return List.of();
    }

    public boolean showAsResourcePack() {
        return false;
    }

    public boolean showAsDataPack() {
        return false;
    }

    private Optional<LoadedMod> mod() {
        return OctoRuntime.isRunning() ? OctoRuntime.get().mod(modId) : Optional.empty();
    }

    @Override
    public String toString() {
        return "Mod File: " + getFileName();
    }
}
