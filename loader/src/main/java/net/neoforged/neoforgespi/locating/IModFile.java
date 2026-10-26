package net.neoforged.neoforgespi.locating;

import java.nio.file.Path;
import java.util.List;

import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * A mod's file, as NeoForge's locators describe it.
 *
 * <p>Mods reach for it to read something out of their own jar —
 * {@code getFile().findResource("data", ...)} — so that is what is answered;
 * the module and secure-jar machinery behind NeoForge's version has no
 * counterpart under Octo.
 */
public interface IModFile {
    Path getFilePath();

    String getFileName();

    /** A path inside the file, whether or not anything is there. */
    Path findResource(String... pathName);

    List<IModInfo> getModInfos();

    IModFileInfo getModFileInfo();
}
