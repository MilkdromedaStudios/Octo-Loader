package studios.milkdromeda.octo.mod.format;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;

import studios.milkdromeda.octo.mod.ModDependency;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;
import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.mod.version.VersionPredicate;

/**
 * Reads {@code META-INF/mods.toml} (Forge, 1.13+) and
 * {@code META-INF/neoforge.mods.toml} (NeoForge, 1.20.5+).
 *
 * <p>The two files are the same format with two differences: NeoForge renamed
 * the file and replaced the boolean {@code mandatory} with a {@code type} enum.
 * Both are handled here.
 *
 * <p>Neither file names the mod's entrypoint — that lives in an {@code @Mod}
 * annotation in the bytecode, which {@link studios.milkdromeda.octo.discovery.ModClassScanner}
 * recovers afterwards.
 */
public final class ForgeMetadataParser implements MetadataParser {
    private final ModFormat format;

    public ForgeMetadataParser(ModFormat format) {
        if (format != ModFormat.FORGE && format != ModFormat.NEOFORGE) {
            throw new IllegalArgumentException("not a TOML mod format: " + format);
        }

        this.format = format;
    }

    @Override
    public ModFormat format() {
        return format;
    }

    @Override
    public List<ModMetadata> parse(ModSource source) throws MetadataException {
        byte[] bytes = source.read(format.metadataPath())
                .orElseThrow(() -> new MetadataException("no " + format.metadataPath() + " in " + source.path()));

        Config config;

        try {
            config = new TomlParser().parse(new StringReader(new String(bytes, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw new MetadataException("malformed " + format.metadataPath() + ": " + e.getMessage(), e);
        }

        List<UnmodifiableConfig> mods = config.getOrElse(List.of("mods"), List.of());

        if (mods.isEmpty()) {
            throw new MetadataException(format.metadataPath() + " declares no [[mods]]");
        }

        String jarVersion = manifestVersion(source);
        String license = string(config, "license", "");
        String loaderVersion = string(config, "loaderVersion", "*");
        List<ModMetadata> out = new ArrayList<>(mods.size());

        for (UnmodifiableConfig mod : mods) {
            String id = string(mod, "modId", null);

            if (id == null) {
                continue;
            }

            ModMetadata.Builder builder = ModMetadata.builder(id, format)
                    .name(string(mod, "displayName", id))
                    .version(resolvePlaceholders(string(mod, "version", "0.0.0"), jarVersion))
                    .description(string(mod, "description", ""))
                    .license(license);

            for (String author : splitAuthors(string(mod, "authors", ""))) {
                builder.author(author);
            }

            // The loader's own version range is the strongest era signal a
            // Forge jar carries: loaderVersion "[47,)" means 1.20.1.
            builder.dependency(new ModDependency(format == ModFormat.NEOFORGE ? "neoforge" : "forge",
                    VersionPredicate.parseRange(loaderVersion), ModDependency.Kind.OPTIONAL,
                    ModDependency.Ordering.NONE));

            readDependencies(config, id, builder);
            readMixins(config, mod, builder);
            readAccessTransformers(config, source, builder);

            out.add(builder.build());
        }

        if (out.isEmpty()) {
            throw new MetadataException(format.metadataPath() + " declares no usable mod");
        }

        return out;
    }

    private void readDependencies(Config config, String modId, ModMetadata.Builder builder) {
        Optional<UnmodifiableConfig> block = config.getOptional(List.of("dependencies"));

        if (block.isEmpty()) {
            return;
        }

        Object declared = block.get().get(List.of(modId));

        if (!(declared instanceof List<?> entries)) {
            return;
        }

        for (Object entry : entries) {
            if (!(entry instanceof UnmodifiableConfig dependency)) {
                continue;
            }

            String id = string(dependency, "modId", null);

            if (id == null) {
                continue;
            }

            String range = string(dependency, "versionRange", "*");

            builder.dependency(new ModDependency(id, VersionPredicate.parseRange(range), kindOf(dependency),
                    orderingOf(string(dependency, "ordering", "NONE"))));

            if (id.equals("minecraft")) {
                builder.declaredMinecraftVersion(range);
            }
        }
    }

    /** Forge says {@code mandatory = true}; NeoForge says {@code type = "required"}. */
    private ModDependency.Kind kindOf(UnmodifiableConfig dependency) {
        String type = string(dependency, "type", null);

        if (type != null) {
            return switch (type.toLowerCase(Locale.ROOT)) {
                case "required" -> ModDependency.Kind.REQUIRED;
                case "incompatible" -> ModDependency.Kind.BREAKS;
                case "discouraged" -> ModDependency.Kind.CONFLICTS;
                default -> ModDependency.Kind.OPTIONAL;
            };
        }

        Object mandatory = dependency.get(List.of("mandatory"));
        boolean required = mandatory instanceof Boolean flag ? flag : Boolean.parseBoolean(String.valueOf(mandatory));
        return required ? ModDependency.Kind.REQUIRED : ModDependency.Kind.OPTIONAL;
    }

    private static ModDependency.Ordering orderingOf(String raw) {
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "BEFORE" -> ModDependency.Ordering.BEFORE;
            case "AFTER" -> ModDependency.Ordering.AFTER;
            default -> ModDependency.Ordering.NONE;
        };
    }

    private static void readMixins(Config config, UnmodifiableConfig mod, ModMetadata.Builder builder) {
        for (Object source : new Object[] { config.get(List.of("mixins")), mod.get(List.of("mixins")) }) {
            if (!(source instanceof List<?> entries)) {
                continue;
            }

            for (Object entry : entries) {
                if (entry instanceof UnmodifiableConfig mixin) {
                    String path = string(mixin, "config", null);

                    if (path != null) {
                        builder.mixinConfig(path);
                    }
                } else if (entry instanceof String path) {
                    builder.mixinConfig(path);
                }
            }
        }
    }

    private static void readAccessTransformers(Config config, ModSource source, ModMetadata.Builder builder) {
        Object declared = config.get(List.of("accessTransformers"));
        boolean any = false;

        if (declared instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof UnmodifiableConfig transformer) {
                    String file = string(transformer, "file", null);

                    if (file != null) {
                        builder.accessTransformer(file);
                        any = true;
                    }
                }
            }
        }

        // Before NeoForge made them explicit, the path was fixed by convention.
        if (!any && source.has("META-INF/accesstransformer.cfg")) {
            builder.accessTransformer("META-INF/accesstransformer.cfg");
        }
    }

    /** {@code ${file.jarVersion}} is substituted by Forge from the jar manifest. */
    private static String resolvePlaceholders(String value, String jarVersion) {
        return value.replace("${file.jarVersion}", jarVersion == null ? "0.0.0" : jarVersion);
    }

    private static String manifestVersion(ModSource source) {
        return source.read("META-INF/MANIFEST.MF").map(bytes -> {
            try {
                Attributes attributes = new Manifest(new java.io.ByteArrayInputStream(bytes)).getMainAttributes();
                String version = attributes.getValue("Implementation-Version");
                return version != null ? version : attributes.getValue("Specification-Version");
            } catch (Exception e) {
                return null;
            }
        }).orElse(null);
    }

    private static List<String> splitAuthors(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        List<String> out = new ArrayList<>();

        for (String part : raw.split("[,;]| and ")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }

        return out;
    }

    private static String string(UnmodifiableConfig config, String key, String fallback) {
        Object value = config.get(List.of(key));
        return value == null ? fallback : String.valueOf(value);
    }
}
