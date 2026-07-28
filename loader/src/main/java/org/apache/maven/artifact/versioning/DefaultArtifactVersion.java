package org.apache.maven.artifact.versioning;

import studios.milkdromeda.octo.mod.version.Version;

/**
 * The version a mod declared, in the shape Forge mods expect to read it.
 *
 * <p>Backed by Octo's own parser, so {@code 1.20.1-0.5.1.f} and {@code 0.6.0-15}
 * order the same way here as they do everywhere else in the loader rather than
 * by a second, subtly different set of rules.
 */
public final class DefaultArtifactVersion implements ArtifactVersion {
    private String raw;
    private Version parsed;
    private String qualifier;

    public DefaultArtifactVersion(String version) {
        parseVersion(version);
    }

    @Override
    public void parseVersion(String version) {
        this.raw = version == null ? "0.0.0" : version;
        this.parsed = Version.parse(raw);

        int dash = raw.indexOf('-');
        this.qualifier = dash >= 0 && dash + 1 < raw.length() ? raw.substring(dash + 1) : null;
    }

    @Override
    public int getMajorVersion() {
        return parsed.part(0);
    }

    @Override
    public int getMinorVersion() {
        return parsed.part(1);
    }

    @Override
    public int getIncrementalVersion() {
        return parsed.part(2);
    }

    @Override
    public int getBuildNumber() {
        return parsed.part(3);
    }

    @Override
    public String getQualifier() {
        return qualifier;
    }

    @Override
    public int compareTo(ArtifactVersion other) {
        if (other == null) {
            return 1;
        }

        return parsed.compareTo(Version.parse(other.toString()));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ArtifactVersion version && compareTo(version) == 0;
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }

    @Override
    public String toString() {
        return raw;
    }
}
