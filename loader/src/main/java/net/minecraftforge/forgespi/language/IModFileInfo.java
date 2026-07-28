package net.minecraftforge.forgespi.language;

import java.util.List;

/** Forge's spelling of {@link net.neoforged.neoforgespi.language.IModFileInfo}. */
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
