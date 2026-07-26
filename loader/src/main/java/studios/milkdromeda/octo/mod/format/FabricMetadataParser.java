package studios.milkdromeda.octo.mod.format;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import studios.milkdromeda.octo.mod.EntrypointSpec;
import studios.milkdromeda.octo.mod.ModDependency;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;
import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.mod.Side;

/** Reads {@code fabric.mod.json} (schema 0 and 1). */
public final class FabricMetadataParser implements MetadataParser {
    private static final Map<String, ModDependency.Kind> DEPENDENCY_FIELDS = Map.of(
            "depends", ModDependency.Kind.REQUIRED,
            "recommends", ModDependency.Kind.OPTIONAL,
            "suggests", ModDependency.Kind.OPTIONAL,
            "conflicts", ModDependency.Kind.CONFLICTS,
            "breaks", ModDependency.Kind.BREAKS);

    @Override
    public ModFormat format() {
        return ModFormat.FABRIC;
    }

    @Override
    public List<ModMetadata> parse(ModSource source) throws MetadataException {
        byte[] bytes = source.read(format().metadataPath())
                .orElseThrow(() -> new MetadataException("no fabric.mod.json in " + source.path()));

        JsonElement root = Json.parse(bytes);

        if (!root.isJsonObject()) {
            throw new MetadataException("fabric.mod.json must be an object");
        }

        JsonObject json = root.getAsJsonObject();
        String id = Json.string(json, "id", null);

        if (id == null) {
            throw new MetadataException("fabric.mod.json has no id");
        }

        ModMetadata.Builder builder = ModMetadata.builder(id, ModFormat.FABRIC)
                .name(Json.string(json, "name", id))
                .version(Json.string(json, "version", "0.0.0"))
                .description(Json.string(json, "description", ""))
                .license(Json.string(json, "license", ""))
                .side(Side.parse(Json.string(json, "environment", "*"), Side.BOTH));

        Json.strings(json, "authors").forEach(builder::author);

        JsonObject entrypoints = Json.object(json, "entrypoints");

        if (entrypoints != null) {
            for (Map.Entry<String, JsonElement> entry : entrypoints.entrySet()) {
                for (JsonElement declaration : Json.array(entry.getValue())) {
                    String value = Json.valueOf(declaration, "value");

                    if (value != null) {
                        builder.entrypoint(entry.getKey(), EntrypointSpec.parse(value, entry.getKey()));
                    }
                }
            }
        }

        for (Map.Entry<String, ModDependency.Kind> field : DEPENDENCY_FIELDS.entrySet()) {
            JsonObject declared = Json.object(json, field.getKey());

            if (declared == null) {
                continue;
            }

            for (Map.Entry<String, JsonElement> entry : declared.entrySet()) {
                String range = joinRanges(entry.getValue());
                builder.dependency(new ModDependency(entry.getKey(),
                        studios.milkdromeda.octo.mod.version.VersionPredicate.parse(range),
                        field.getValue(), ModDependency.Ordering.NONE));

                if (entry.getKey().equals("minecraft")) {
                    builder.declaredMinecraftVersion(range);
                }
            }
        }

        for (JsonElement mixin : Json.list(json, "mixins")) {
            String config = Json.valueOf(mixin, "config");

            if (config != null) {
                builder.mixinConfig(config);
            }
        }

        String accessWidener = Json.string(json, "accessWidener", null);

        if (accessWidener != null) {
            builder.accessWidener(accessWidener);
        }

        for (JsonElement nested : Json.list(json, "jars")) {
            String file = Json.valueOf(nested, "file");

            if (file != null) {
                builder.nestedJar(file);
            }
        }

        return List.of(builder.build());
    }

    /** Fabric allows a single predicate or an array of them; an array is an OR. */
    private static String joinRanges(JsonElement element) {
        if (element.isJsonArray()) {
            StringBuilder out = new StringBuilder();

            for (JsonElement child : element.getAsJsonArray()) {
                if (out.length() > 0) {
                    out.append(" || ");
                }

                out.append(child.getAsString());
            }

            return out.length() == 0 ? "*" : out.toString();
        }

        return element.isJsonPrimitive() ? element.getAsString() : "*";
    }
}
