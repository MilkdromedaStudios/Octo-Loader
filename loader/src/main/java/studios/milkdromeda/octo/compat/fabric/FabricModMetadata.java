package studios.milkdromeda.octo.compat.fabric;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.ContactInformation;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModEnvironment;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.fabricmc.loader.impl.util.version.VersionParser;

import studios.milkdromeda.octo.mod.Side;

/** Presents Octo's canonical metadata through Fabric's metadata interface. */
final class FabricModMetadata implements ModMetadata {
    private final studios.milkdromeda.octo.mod.ModMetadata source;
    private final Version version;

    FabricModMetadata(studios.milkdromeda.octo.mod.ModMetadata source) {
        this.source = source;
        this.version = parseVersion(source.version().raw());
    }

    private static Version parseVersion(String raw) {
        try {
            return VersionParser.parse(raw, false);
        } catch (VersionParsingException e) {
            // Fabric's parser is stricter than Octo's; fall back to the raw string
            // rather than refusing to describe a mod that loaded fine.
            return new net.fabricmc.loader.impl.util.version.StringVersion(raw);
        }
    }

    @Override
    public String getType() {
        return source.format().name().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public String getId() {
        return source.id();
    }

    @Override
    public Collection<String> getProvides() {
        return List.of();
    }

    @Override
    public Version getVersion() {
        return version;
    }

    @Override
    public ModEnvironment getEnvironment() {
        Side side = source.side();
        return side == Side.CLIENT ? ModEnvironment.CLIENT
                : side == Side.SERVER ? ModEnvironment.SERVER : ModEnvironment.UNIVERSAL;
    }

    @Override
    public Collection<ModDependency> getDependencies() {
        List<ModDependency> out = new ArrayList<>();

        for (studios.milkdromeda.octo.mod.ModDependency dependency : source.dependencies()) {
            out.add(new FabricModDependency(dependency));
        }

        return out;
    }

    @Override
    public String getName() {
        return source.name();
    }

    @Override
    public String getDescription() {
        return source.description();
    }

    @Override
    public Collection<Person> getAuthors() {
        List<Person> out = new ArrayList<>();

        for (String author : source.authors()) {
            out.add(new SimplePerson(author));
        }

        return out;
    }

    @Override
    public Collection<Person> getContributors() {
        return List.of();
    }

    @Override
    public ContactInformation getContact() {
        return ContactInformation.EMPTY;
    }

    @Override
    public Collection<String> getLicense() {
        return source.license().isBlank() ? List.of() : List.of(source.license());
    }

    @Override
    public Optional<String> getIconPath(int size) {
        return Optional.empty();
    }

    @Override
    public boolean containsCustomValue(String key) {
        return false;
    }

    @Override
    public CustomValue getCustomValue(String key) {
        return null;
    }

    @Override
    public Map<String, CustomValue> getCustomValues() {
        return Map.of();
    }

    @Override
    @Deprecated
    public boolean containsCustomElement(String key) {
        return false;
    }

    private record SimplePerson(String name) implements Person {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public ContactInformation getContact() {
            return ContactInformation.EMPTY;
        }
    }
}
