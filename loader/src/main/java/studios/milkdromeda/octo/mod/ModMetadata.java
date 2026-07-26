package studios.milkdromeda.octo.mod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import studios.milkdromeda.octo.mod.version.Version;

/**
 * The one shape every mod is reduced to, whichever file it arrived in.
 *
 * <p>Once discovery has produced these, nothing downstream — resolution,
 * transformation, entrypoint dispatch — has to care which loader the author
 * originally targeted.
 */
public final class ModMetadata {
    private final String id;
    private final String name;
    private final Version version;
    private final ModFormat format;
    private final Side side;
    private final String description;
    private final List<String> authors;
    private final String license;
    private final Map<String, List<EntrypointSpec>> entrypoints;
    private final List<ModDependency> dependencies;
    private final List<String> mixinConfigs;
    private final List<String> accessWideners;
    private final List<String> accessTransformers;
    private final List<String> nestedJars;
    private final String declaredMinecraftVersion;

    private ModMetadata(Builder builder) {
        this.id = builder.id;
        this.name = builder.name != null ? builder.name : builder.id;
        this.version = builder.version;
        this.format = builder.format;
        this.side = builder.side;
        this.description = builder.description;
        this.authors = List.copyOf(builder.authors);
        this.license = builder.license;
        this.dependencies = List.copyOf(builder.dependencies);
        this.mixinConfigs = List.copyOf(builder.mixinConfigs);
        this.accessWideners = List.copyOf(builder.accessWideners);
        this.accessTransformers = List.copyOf(builder.accessTransformers);
        this.nestedJars = List.copyOf(builder.nestedJars);
        this.declaredMinecraftVersion = builder.declaredMinecraftVersion;

        Map<String, List<EntrypointSpec>> copy = new LinkedHashMap<>();
        builder.entrypoints.forEach((phase, specs) -> copy.put(phase, List.copyOf(specs)));
        this.entrypoints = Collections.unmodifiableMap(copy);
    }

    public static Builder builder(String id, ModFormat format) {
        return new Builder(id, format);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Version version() {
        return version;
    }

    public ModFormat format() {
        return format;
    }

    public Side side() {
        return side;
    }

    public String description() {
        return description;
    }

    public List<String> authors() {
        return authors;
    }

    public String license() {
        return license;
    }

    public Map<String, List<EntrypointSpec>> entrypoints() {
        return entrypoints;
    }

    public List<EntrypointSpec> entrypoints(String phase) {
        return entrypoints.getOrDefault(phase, List.of());
    }

    public List<EntrypointSpec> allEntrypoints() {
        List<EntrypointSpec> out = new ArrayList<>();
        entrypoints.values().forEach(out::addAll);
        return out;
    }

    public List<ModDependency> dependencies() {
        return dependencies;
    }

    public List<String> mixinConfigs() {
        return mixinConfigs;
    }

    public List<String> accessWideners() {
        return accessWideners;
    }

    public List<String> accessTransformers() {
        return accessTransformers;
    }

    /** Paths inside this jar holding further mod jars (Fabric/Quilt {@code jars}, Forge JarJar). */
    public List<String> nestedJars() {
        return nestedJars;
    }

    /** The Minecraft version the author wrote down, if any — the strongest era hint available. */
    public Optional<String> declaredMinecraftVersion() {
        return Optional.ofNullable(declaredMinecraftVersion);
    }

    /** The Minecraft version range this mod asks for, from whichever field its format uses. */
    public Optional<ModDependency> minecraftDependency() {
        return dependencies.stream().filter(d -> d.modId().equals("minecraft")).findFirst();
    }

    public Builder toBuilder() {
        Builder builder = new Builder(id, format);
        builder.name = name;
        builder.version = version;
        builder.side = side;
        builder.description = description;
        builder.authors.addAll(authors);
        builder.license = license;
        builder.dependencies.addAll(dependencies);
        builder.mixinConfigs.addAll(mixinConfigs);
        builder.accessWideners.addAll(accessWideners);
        builder.accessTransformers.addAll(accessTransformers);
        builder.nestedJars.addAll(nestedJars);
        builder.declaredMinecraftVersion = declaredMinecraftVersion;
        entrypoints.forEach((phase, specs) -> builder.entrypoints.put(phase, new ArrayList<>(specs)));
        return builder;
    }

    @Override
    public String toString() {
        return id + " " + version + " (" + format.displayName() + ")";
    }

    public static final class Builder {
        private final String id;
        private final ModFormat format;
        private String name;
        private Version version = Version.parse("0.0.0");
        private Side side = Side.BOTH;
        private String description = "";
        private final List<String> authors = new ArrayList<>();
        private String license = "";
        private final Map<String, List<EntrypointSpec>> entrypoints = new LinkedHashMap<>();
        private final List<ModDependency> dependencies = new ArrayList<>();
        private final List<String> mixinConfigs = new ArrayList<>();
        private final List<String> accessWideners = new ArrayList<>();
        private final List<String> accessTransformers = new ArrayList<>();
        private final List<String> nestedJars = new ArrayList<>();
        private String declaredMinecraftVersion;

        private Builder(String id, ModFormat format) {
            this.id = id;
            this.format = format;
        }

        public Builder name(String value) {
            this.name = value == null || value.isBlank() ? null : value;
            return this;
        }

        public Builder version(String value) {
            this.version = Version.parse(value);
            return this;
        }

        public Builder side(Side value) {
            this.side = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value == null ? "" : value;
            return this;
        }

        public Builder author(String value) {
            if (value != null && !value.isBlank()) {
                this.authors.add(value.trim());
            }

            return this;
        }

        public Builder license(String value) {
            this.license = value == null ? "" : value;
            return this;
        }

        public Builder entrypoint(String phase, EntrypointSpec spec) {
            this.entrypoints.computeIfAbsent(phase, key -> new ArrayList<>()).add(spec);
            return this;
        }

        public Builder dependency(ModDependency value) {
            this.dependencies.add(value);
            return this;
        }

        public Builder mixinConfig(String value) {
            this.mixinConfigs.add(value);
            return this;
        }

        public Builder accessWidener(String value) {
            this.accessWideners.add(value);
            return this;
        }

        public Builder accessTransformer(String value) {
            this.accessTransformers.add(value);
            return this;
        }

        public Builder nestedJar(String value) {
            this.nestedJars.add(value);
            return this;
        }

        public Builder declaredMinecraftVersion(String value) {
            this.declaredMinecraftVersion = value == null || value.isBlank() ? null : value.trim();
            return this;
        }

        public ModMetadata build() {
            return new ModMetadata(this);
        }
    }
}
