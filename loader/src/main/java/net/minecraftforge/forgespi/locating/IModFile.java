package net.minecraftforge.forgespi.locating;

import java.nio.file.Path;
import java.util.List;

import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;

/** Forge's spelling of {@link net.neoforged.neoforgespi.locating.IModFile}. */
public interface IModFile {
    Path getFilePath();

    String getFileName();

    /** A path inside the file, whether or not anything is there. */
    Path findResource(String... pathName);

    List<IModInfo> getModInfos();

    IModFileInfo getModFileInfo();
}
