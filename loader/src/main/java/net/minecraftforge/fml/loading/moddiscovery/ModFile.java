package net.minecraftforge.fml.loading.moddiscovery;

import java.nio.file.Path;
import java.util.List;

import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.locating.IModFile;

import studios.milkdromeda.octo.bridge.forge.ModInfos;
import studios.milkdromeda.octo.compat.fabric.JarFileSystems;

/**
 * The jar a Forge mod came from.
 *
 * <p>{@code ModFileInfo.getFile()} hands out this class rather than the
 * interface, and mods use it to look at their own jar: Collective's mixin
 * plugin asks for its file path to tell whether it was bundled inside another
 * mod, and a null there failed every one of its mixins.
 */
public final class ModFile implements IModFile {
    private final ModFileInfo info;

    ModFile(ModFileInfo info) {
        this.info = info;
    }

    @Override
    public Path getFilePath() {
        return ModInfos.pathOf(info.getModId());
    }

    @Override
    public String getFileName() {
        return info.getFileName();
    }

    @Override
    public Path findResource(String... pathName) {
        Path jar = getFilePath();

        if (jar == null) {
            return null;
        }

        try {
            Path found = JarFileSystems.root(jar);

            for (String part : pathName) {
                found = found.resolve(part);
            }

            return found;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public List<IModInfo> getModInfos() {
        return info.getMods();
    }

    @Override
    public IModFileInfo getModFileInfo() {
        return info;
    }

    @Override
    public String toString() {
        return "Mod File: " + getFilePath();
    }
}
