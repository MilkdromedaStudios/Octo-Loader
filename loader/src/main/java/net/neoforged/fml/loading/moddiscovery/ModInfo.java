package net.neoforged.fml.loading.moddiscovery;

import java.util.Optional;

import net.neoforged.neoforgespi.language.IModInfo;

/**
 * FML's own class for a mod's description, which mods cast to.
 *
 * <p>{@code LoadingModList.getMods()} hands out {@code ModInfo}, not the
 * interface, and mods take it at its word: FerriteCore casts every entry to
 * this class to read its own option overrides out of other mods' metadata. So
 * Octo's descriptions are this class, not merely the interface it implements.
 */
public abstract class ModInfo implements IModInfo {
    /**
     * A value from the mod's own section of its metadata. Octo keeps no custom
     * keys, so there is never one to give.
     */
    public Optional<Object> getConfigElement(String... key) {
        return Optional.empty();
    }

    @Override
    public abstract ModFileInfo getOwningFile();
}
