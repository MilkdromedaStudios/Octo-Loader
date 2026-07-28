package net.minecraftforge.forgespi.language;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

/**
 * Forge's spelling of {@link net.neoforged.neoforgespi.language.IModInfo}.
 *
 * <p>The two are separate types with the same shape, because a mod compiled
 * against Forge and a mod compiled against NeoForge each link against their own
 * and neither will accept the other's.
 */
public interface IModInfo {
    String getModId();

    String getNamespace();

    ArtifactVersion getVersion();

    String getDisplayName();

    String getDescription();

    IModFileInfo getOwningFile();

    List<? extends ModVersion> getDependencies();

    Optional<String> getLogoFile();

    boolean getLogoBlur();

    Optional<String> getUpdateURL();

    Map<String, Object> getModProperties();

    /** One entry of the mod's {@code dependencies} block. */
    interface ModVersion {
        String getModId();

        VersionRange getVersionRange();

        boolean isMandatory();

        Ordering getOrdering();

        DependencySide getSide();
    }

    /** Whether the dependency has to load before or after this mod. */
    enum Ordering {
        BEFORE, AFTER, NONE
    }

    /** Which side the dependency is required on. */
    enum DependencySide {
        CLIENT, SERVER, BOTH
    }
}
