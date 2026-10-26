package net.minecraftforge.fml.loading.moddiscovery;

import java.util.Optional;

import net.minecraftforge.forgespi.language.IModInfo;

/**
 * Forge's own class for a mod's description, which mods cast to.
 *
 * <p>{@code LoadingModList.getMods()} hands out {@code ModInfo}, not the
 * interface, so Octo's descriptions are this class.
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
