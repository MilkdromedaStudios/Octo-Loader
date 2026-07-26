package studios.milkdromeda.octo.mod.format;

import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import studios.milkdromeda.octo.mod.ModDependency;
import studios.milkdromeda.octo.mod.ModFormat;
import studios.milkdromeda.octo.mod.ModMetadata;
import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.mod.Side;

/**
 * Reads {@code litemod.json} — LiteLoader, 1.5.2 through 1.12.2.
 *
 * <p>LiteMods were client-side utility mods shipped as {@code .litemod} files.
 * Their entrypoint is a class implementing {@code com.mumfrey.liteloader.LiteMod},
 * found by the class scanner; the JSON carries only descriptive data.
 */
public final class LiteLoaderMetadataParser implements MetadataParser {
    @Override
    public ModFormat format() {
        return ModFormat.LITELOADER;
    }

    @Override
    public List<ModMetadata> parse(ModSource source) throws MetadataException {
        byte[] bytes = source.read(format().metadataPath())
                .orElseThrow(() -> new MetadataException("no litemod.json in " + source.path()));

        JsonElement root = Json.parse(bytes);

        if (!root.isJsonObject()) {
            throw new MetadataException("litemod.json must be an object");
        }

        JsonObject json = root.getAsJsonObject();
        String name = Json.string(json, "name", null);

        if (name == null) {
            throw new MetadataException("litemod.json has no name");
        }

        String mcVersion = Json.string(json, "mcversion", null);

        ModMetadata.Builder builder = ModMetadata.builder(name.toLowerCase(java.util.Locale.ROOT), ModFormat.LITELOADER)
                .name(Json.string(json, "displayName", name))
                .version(Json.string(json, "version", Json.string(json, "revision", "0.0.0")))
                .description(Json.string(json, "description", ""))
                .author(Json.string(json, "author", null))
                // LiteLoader never ran on a dedicated server.
                .side(Side.CLIENT)
                .declaredMinecraftVersion(mcVersion);

        if (mcVersion != null && !mcVersion.isBlank()) {
            builder.dependency(ModDependency.required("minecraft", mcVersion));
        }

        return List.of(builder.build());
    }
}
