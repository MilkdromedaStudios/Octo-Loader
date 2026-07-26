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
import studios.milkdromeda.octo.mod.version.VersionPredicate;

/**
 * Reads {@code quilt.mod.json}.
 *
 * <p>Quilt nests everything under {@code quilt_loader} and writes dependencies
 * as a list of objects rather than a map, but the model underneath is Fabric's.
 */
public final class QuiltMetadataParser implements MetadataParser {
    @Override
    public ModFormat format() {
        return ModFormat.QUILT;
    }

    @Override
    public List<ModMetadata> parse(ModSource source) throws MetadataException {
        byte[] bytes = source.read(format().metadataPath())
                .orElseThrow(() -> new MetadataException("no quilt.mod.json in " + source.path()));

        JsonElement root = Json.parse(bytes);

        if (!root.isJsonObject()) {
            throw new MetadataException("quilt.mod.json must be an object");
        }

        JsonObject json = root.getAsJsonObject();
        JsonObject loader = Json.object(json, "quilt_loader");

        if (loader == null) {
            throw new MetadataException("quilt.mod.json has no quilt_loader block");
        }

        String id = Json.string(loader, "id", null);

        if (id == null) {
            throw new MetadataException("quilt.mod.json has no id");
        }

        JsonObject metadata = Json.object(loader, "metadata");
        JsonObject minecraft = Json.object(json, "minecraft");

        ModMetadata.Builder builder = ModMetadata.builder(id, ModFormat.QUILT)
                .name(Json.string(metadata, "name", id))
                .version(Json.string(loader, "version", "0.0.0"))
                .description(Json.string(metadata, "description", ""))
                .license(Json.string(metadata, "license", ""))
                .side(Side.parse(Json.string(minecraft, "environment", "*"), Side.BOTH));

        JsonObject contributors = Json.object(metadata, "contributors");

        if (contributors != null) {
            contributors.keySet().forEach(builder::author);
        }

        JsonObject entrypoints = Json.object(loader, "entrypoints");

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

        readDependencies(loader, "depends", builder);
        readDependencies(loader, "breaks", builder);

        for (JsonElement mixin : Json.list(json, "mixin")) {
            String config = Json.valueOf(mixin, "config");

            if (config != null) {
                builder.mixinConfig(config);
            }
        }

        for (JsonElement widener : Json.list(json, "access_widener")) {
            String path = Json.valueOf(widener, "value");

            if (path != null) {
                builder.accessWidener(path);
            }
        }

        for (JsonElement nested : Json.list(loader, "jars")) {
            String file = Json.valueOf(nested, "value");

            if (file != null) {
                builder.nestedJar(file);
            }
        }

        return List.of(builder.build());
    }

    private static void readDependencies(JsonObject loader, String field, ModMetadata.Builder builder) {
        boolean negative = field.equals("breaks");

        for (JsonElement element : Json.list(loader, field)) {
            String id;
            String versions = "*";
            boolean optional = false;

            if (element.isJsonPrimitive()) {
                id = element.getAsString();
            } else if (element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                id = Json.string(object, "id", null);
                versions = readVersions(object);
                optional = Json.isTrue(object, "optional", false);
            } else {
                continue;
            }

            if (id == null) {
                continue;
            }

            // Quilt ids are "group:id"; Octo keys mods by the short id.
            int colon = id.indexOf(':');

            if (colon >= 0) {
                id = id.substring(colon + 1);
            }

            ModDependency.Kind kind = negative ? ModDependency.Kind.BREAKS
                    : optional ? ModDependency.Kind.OPTIONAL : ModDependency.Kind.REQUIRED;

            builder.dependency(new ModDependency(id, VersionPredicate.parse(versions), kind,
                    ModDependency.Ordering.NONE));

            if (id.equals("minecraft")) {
                builder.declaredMinecraftVersion(versions);
            }
        }
    }

    /** {@code "versions"} is a predicate string, a list ("any"/"all"), or an object. */
    private static String readVersions(JsonObject object) {
        JsonElement versions = object.get("versions");

        if (versions == null || versions.isJsonNull()) {
            return "*";
        }

        if (versions.isJsonPrimitive()) {
            return versions.getAsString();
        }

        if (versions.isJsonArray()) {
            StringBuilder out = new StringBuilder();

            for (JsonElement child : versions.getAsJsonArray()) {
                if (child.isJsonPrimitive()) {
                    if (out.length() > 0) {
                        out.append(" || ");
                    }

                    out.append(child.getAsString());
                }
            }

            return out.length() == 0 ? "*" : out.toString();
        }

        if (versions.isJsonObject()) {
            JsonObject nested = versions.getAsJsonObject();
            List<String> any = Json.strings(nested, "any");

            if (!any.isEmpty()) {
                return String.join(" || ", any);
            }

            List<String> all = Json.strings(nested, "all");

            if (!all.isEmpty()) {
                return String.join(" ", all);
            }
        }

        return "*";
    }
}
