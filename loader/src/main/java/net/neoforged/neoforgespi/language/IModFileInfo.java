package net.neoforged.neoforgespi.language;

import java.util.List;

/**
 * The file one or more mods were read from.
 *
 * <p>NeoForge's own version describes a secure jar and a module, neither of
 * which exists under Octo. What a mod actually reaches for — the mods inside,
 * the file name, the loader it declared — is answered properly; the rest is not
 * pretended at.
 */
public interface IModFileInfo {
    List<IModInfo> getMods();

    String getFileName();

    String moduleName();

    String versionString();

    List<String> usesServices();

    String getModLoader();

    String getModLoaderVersion();

    String getLicense();

    boolean showAsResourcePack();

    boolean showAsDataPack();
}
