package org.apache.maven.artifact.versioning;

import studios.milkdromeda.octo.mod.version.VersionPredicate;

/**
 * The range a Forge mod's dependency was declared over.
 *
 * <p>Forge writes these in Maven's interval notation — {@code [1.20.1,1.21)} —
 * which Octo's own predicate parser already understands, so a range read off a
 * {@code mods.toml} answers the same question here as it does during resolution.
 */
public final class VersionRange {
    private final String spec;
    private final VersionPredicate predicate;

    private VersionRange(String spec) {
        this.spec = spec == null ? "" : spec;
        this.predicate = VersionPredicate.parseRange(this.spec);
    }

    public static VersionRange createFromVersionSpec(String spec) {
        return new VersionRange(spec);
    }

    public boolean containsVersion(ArtifactVersion version) {
        return version != null
                && predicate.test(studios.milkdromeda.octo.mod.version.Version.parse(version.toString()));
    }

    public boolean hasRestrictions() {
        return !spec.isBlank();
    }

    @Override
    public String toString() {
        return spec;
    }
}
