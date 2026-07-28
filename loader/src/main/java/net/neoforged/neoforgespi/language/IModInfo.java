package net.neoforged.neoforgespi.language;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.artifact.versioning.ArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;

/**
 * What one mod says about itself, in the shape NeoForge publishes it.
 *
 * <p>This is the type on the other end of {@code getModFileById(id).getMods()},
 * which is how essentially every large NeoForge mod asks whether another mod is
 * installed and which version of it. Create's compatibility layer does it from
 * an enum constructor, so the {@code NoSuchMethodError} on {@code getVersion()}
 * came out during class initialisation and took the whole mod with it.
 *
 * <p>Octo answers from its own runtime, so a NeoForge mod asking about a Fabric
 * mod gets a real answer rather than "not installed".
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
