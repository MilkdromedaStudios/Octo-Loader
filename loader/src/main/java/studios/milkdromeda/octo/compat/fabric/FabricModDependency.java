package studios.milkdromeda.octo.compat.fabric;

import java.util.Collection;
import java.util.List;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.version.VersionInterval;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

/**
 * Presents an Octo dependency through Fabric's interface.
 *
 * <p>The version test is answered by Octo's own predicate rather than Fabric's,
 * because the constraint may have come from a Forge {@code versionRange} that
 * Fabric's parser would reject outright.
 */
final class FabricModDependency implements ModDependency {
    private final studios.milkdromeda.octo.mod.ModDependency source;

    FabricModDependency(studios.milkdromeda.octo.mod.ModDependency source) {
        this.source = source;
    }

    @Override
    public Kind getKind() {
        return switch (source.kind()) {
            case REQUIRED -> Kind.DEPENDS;
            case OPTIONAL -> Kind.RECOMMENDS;
            case CONFLICTS -> Kind.CONFLICTS;
            case BREAKS -> Kind.BREAKS;
        };
    }

    @Override
    public String getModId() {
        return source.modId();
    }

    @Override
    public boolean matches(Version version) {
        return source.versions().test(studios.milkdromeda.octo.mod.version.Version.parse(version.getFriendlyString()));
    }

    @Override
    public Collection<VersionPredicate> getVersionRequirements() {
        return List.of();
    }

    @Override
    public List<VersionInterval> getVersionIntervals() {
        return List.of(VersionInterval.INFINITE);
    }

    @Override
    public String toString() {
        return source.toString();
    }
}
