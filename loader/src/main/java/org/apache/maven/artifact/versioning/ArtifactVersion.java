package org.apache.maven.artifact.versioning;

/**
 * A version, as Forge and NeoForge hand one back.
 *
 * <p>Not a loader API at all — it is Maven's, and it is here because Forge chose
 * it as the return type of {@code IModInfo.getVersion()}. A mod asking another
 * mod for its version links against this interface, so a runtime without it
 * fails at the call site with the {@code NoSuchMethodError} on
 * {@code IModInfo.getVersion()} that Create's compatibility layer produced.
 *
 * <p>Only the part of Maven's interface that mods actually reach is declared.
 * Maven itself is not a dependency of the loader and must not become one: a
 * player's mods folder frequently contains a copy of it, and two copies of a
 * type a mod casts between is worse than none.
 */
public interface ArtifactVersion extends Comparable<ArtifactVersion> {
    int getMajorVersion();

    int getMinorVersion();

    int getIncrementalVersion();

    int getBuildNumber();

    String getQualifier();

    void parseVersion(String version);
}
