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
    private final Map<String, Side> mixinSides;
    private final List<String> accessWideners;
    private final List<String> accessTransformers;
    private final List<String> nestedJars;
    private final String declaredMinecraftVersion;
    private final List<String> provides;
    private final Map<String, Object> customValues;
    private final String icon;

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
        this.mixinSides = Map.copyOf(builder.mixinSides);
        this.accessWideners = List.copyOf(builder.accessWideners);
        this.accessTransformers = List.copyOf(builder.accessTransformers);
        this.nestedJars = List.copyOf(builder.nestedJars);
        this.declaredMinecraftVersion = builder.declaredMinecraftVersion;
        this.provides = List.copyOf(builder.provides);
        this.customValues = Collections.unmodifiableMap(new LinkedHashMap<>(builder.customValues));
        this.icon = builder.icon;

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

    /**
     * The mixin configs that belong on a side.
     *
     * <p>Fabric and Quilt let a mod mark a whole config {@code "environment":
     * "client"}, and inside such a config the plain {@code mixins} array is
     * client code too: Fabric API's content registries put {@code HudMixin}
     * there. Registered on a dedicated server, mixin went looking for
     * {@code net.minecraft.client.gui.Hud}, failed the required config, and the
     * failure stopped every config after it from being prepared — registry sync
     * and the rest of Fabric API's mixins included.
     */
    public List<String> mixinConfigs(Side physical) {
        List<String> out = new ArrayList<>(mixinConfigs.size());

        for (String config : mixinConfigs) {
            if (mixinSides.getOrDefault(config, Side.BOTH).runsOn(physical)) {
                out.add(config);
            }
        }

        return out;
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

    /** Further ids this mod answers for: Fabric's and Quilt's {@code provides}. */
    public List<String> provides() {
        return provides;
    }

    /**
     * The free-form values a mod published for other mods to read: Fabric's
     * {@code custom} block, and the fields Quilt leaves to mods. Each is a
     * plain value: a map, a list, a string, a number, a boolean, or null.
     *
     * <p>Mods steer each other through these. Sodium says
     * {@code fabric-renderer-api-v1:contains_renderer} so that Fabric API's own
     * renderer stands aside; without it both registered, and the second
     * registration threw.
     */
    public Map<String, Object> customValues() {
        return customValues;
    }

    /**
     * The mod's icon, as a path inside its jar: Fabric's {@code icon}, Quilt's
     * {@code metadata.icon}, Forge's and NeoForge's {@code logoFile}. Mod lists
     * show it beside the mod's name.
     */
    public Optional<String> icon() {
        return Optional.ofNullable(icon);
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
        builder.mixinSides.putAll(mixinSides);
        builder.accessWideners.addAll(accessWideners);
        builder.accessTransformers.addAll(accessTransformers);
        builder.nestedJars.addAll(nestedJars);
        builder.declaredMinecraftVersion = declaredMinecraftVersion;
        builder.provides.addAll(provides);
        builder.customValues.putAll(customValues);
        builder.icon = icon;
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
        private final Map<String, Side> mixinSides = new LinkedHashMap<>();
        private final List<String> accessWideners = new ArrayList<>();
        private final List<String> accessTransformers = new ArrayList<>();
        private final List<String> nestedJars = new ArrayList<>();
        private String declaredMinecraftVersion;
        private final List<String> provides = new ArrayList<>();
        private final Map<String, Object> customValues = new LinkedHashMap<>();
        private String icon;

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

        public Builder mixinConfig(String value, Side side) {
            mixinConfig(value);

            if (side != null && side != Side.BOTH) {
                this.mixinSides.put(value, side);
            }

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

        public Builder provide(String value) {
            if (value != null && !value.isBlank() && !value.equals(id)) {
                this.provides.add(value.trim());
            }

            return this;
        }

        public Builder customValue(String key, Object value) {
            this.customValues.put(key, value);
            return this;
        }

        /** The icon's path inside the jar; a leading slash, which some mods write, is dropped. */
        public Builder icon(String value) {
            String trimmed = value == null ? "" : value.trim();

            while (trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1);
            }

            this.icon = trimmed.isEmpty() ? null : trimmed;
            return this;
        }

        public ModMetadata build() {
            return new ModMetadata(this);
        }
    }
}
