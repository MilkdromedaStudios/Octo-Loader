package studios.milkdromeda.octo.mod.format;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import studios.milkdromeda.octo.mod.ModDependency;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;
import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.mod.version.VersionPredicate;

/**
 * Reads {@code mcmod.info} — Forge's metadata file from 1.2.5 through 1.12.2.
 *
 * <p>This is the format nothing else still reads, and it covers roughly a decade
 * of mods. Two shapes exist in the wild: a bare array of mod objects, and the
 * later {@code {"modListVersion": 2, "modList": [...]}} wrapper. Both appear here.
 *
 * <p>Dependencies were written as free text — {@code "required-after:Forge@[10.13,)"},
 * {@code "before:AnotherMod"} — so they are parsed leniently and, when a version
 * cannot be understood, kept as an unversioned relationship rather than dropped.
 */
public final class LegacyForgeMetadataParser implements MetadataParser {
    @Override
    public ModFormat format() {
        return ModFormat.LEGACY_FORGE;
    }

    @Override
    public List<ModMetadata> parse(ModSource source) throws MetadataException {
        byte[] bytes = source.read(format().metadataPath())
                .orElseThrow(() -> new MetadataException("no mcmod.info in " + source.path()));

        JsonElement root = Json.parse(bytes);
        JsonArray mods;

        if (root.isJsonArray()) {
            mods = root.getAsJsonArray();
        } else if (root.isJsonObject() && root.getAsJsonObject().has("modList")) {
            mods = Json.array(root.getAsJsonObject().get("modList"));
        } else if (root.isJsonObject()) {
            mods = new JsonArray();
            mods.add(root);
        } else {
            throw new MetadataException("mcmod.info is neither an array nor a modList object");
        }

        List<ModMetadata> out = new ArrayList<>();

        for (JsonElement element : mods) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject mod = element.getAsJsonObject();
            String id = Json.string(mod, "modid", null);

            if (id == null) {
                continue;
            }

            String mcVersion = Json.string(mod, "mcversion", null);

            ModMetadata.Builder builder = ModMetadata.builder(id, ModFormat.LEGACY_FORGE)
                    .name(Json.string(mod, "name", id))
                    .version(Json.string(mod, "version", "0.0.0"))
                    .description(Json.string(mod, "description", ""))
                    .license(Json.string(mod, "credits", ""))
                    .declaredMinecraftVersion(mcVersion);

            Json.strings(mod, "authorList").forEach(builder::author);
            Json.strings(mod, "authors").forEach(builder::author);

            if (mcVersion != null && !mcVersion.isBlank()) {
                builder.dependency(ModDependency.required("minecraft", mcVersion));
            }

            for (String dependency : Json.strings(mod, "requiredMods")) {
                addDependency(builder, dependency, ModDependency.Kind.REQUIRED);
            }

            for (String dependency : Json.strings(mod, "dependencies")) {
                addDependency(builder, dependency, ModDependency.Kind.OPTIONAL);
            }

            for (String dependency : Json.strings(mod, "dependants")) {
                addDependency(builder, dependency, ModDependency.Kind.OPTIONAL);
            }

            out.add(builder.build());
        }

        if (out.isEmpty()) {
            throw new MetadataException("mcmod.info declares no usable mod");
        }

        return out;
    }

    /**
     * Parses one entry of FML's dependency string language:
     * {@code [required-]{before|after}:<modid>[@<versionRange>]}.
     */
    private static void addDependency(ModMetadata.Builder builder, String raw, ModDependency.Kind fallbackKind) {
        String text = raw.trim();

        if (text.isEmpty()) {
            return;
        }

        ModDependency.Kind kind = fallbackKind;
        ModDependency.Ordering ordering = ModDependency.Ordering.NONE;

        int colon = text.lastIndexOf(':');

        if (colon >= 0) {
            String prefix = text.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
            text = text.substring(colon + 1);

            if (prefix.startsWith("required")) {
                kind = ModDependency.Kind.REQUIRED;
            }

            if (prefix.endsWith("before")) {
                ordering = ModDependency.Ordering.BEFORE;
            } else if (prefix.endsWith("after")) {
                ordering = ModDependency.Ordering.AFTER;
            }
        }

        String range = "*";
        int at = text.indexOf('@');

        if (at >= 0) {
            range = text.substring(at + 1).trim();
            text = text.substring(0, at);
        }

        String id = text.trim();

        if (id.isEmpty()) {
            return;
        }

        builder.dependency(new ModDependency(id, VersionPredicate.parseRange(range), kind, ordering));
    }
}
