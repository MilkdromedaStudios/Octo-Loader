package studios.milkdromeda.octoloader.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import studios.milkdromeda.octoloader.detect.DetectedMod;
import studios.milkdromeda.octoloader.detect.ModEcosystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The per-loader, per-era knowledge Octo Loader ships with — each loader
 * version's documented behavior (metadata format, mappings, translatability)
 * encoded as JSON resources under {@code octoloader/knowledge/}, plus the
 * Minecraft version timeline and the per-version API documentation chain.
 */
public final class KnowledgeBase {
    private final Map<ModEcosystem, List<Era>> eras = new EnumMap<>(ModEcosystem.class);
    private McVersionIndex versionIndex;
    private ApiDocs apiDocs;

    public static KnowledgeBase load() {
        KnowledgeBase kb = new KnowledgeBase();
        for (ModEcosystem eco : ModEcosystem.values()) {
            if (eco == ModEcosystem.UNKNOWN) continue;
            String resource = "/octoloader/knowledge/" + eco.name().toLowerCase() + ".json";
            try (InputStream in = KnowledgeBase.class.getResourceAsStream(resource)) {
                if (in == null) continue;
                kb.eras.put(eco, parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Malformed knowledge resource " + resource, e);
            }
        }
        String indexResource = "/octoloader/knowledge/minecraft_versions.json";
        try (InputStream in = KnowledgeBase.class.getResourceAsStream(indexResource)) {
            kb.versionIndex = McVersionIndex.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Malformed knowledge resource " + indexResource, e);
        }
        kb.apiDocs = ApiDocs.load(kb.versionIndex);
        return kb;
    }

    /** The documented Minecraft version timeline (Beta 1.7.3 → current). */
    public McVersionIndex versionIndex() {
        return versionIndex;
    }

    /** The per-version-step API documentation chain. */
    public ApiDocs apiDocs() {
        return apiDocs;
    }

    private static List<Era> parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Era> result = new ArrayList<>();
        JsonArray eras = root.getAsJsonArray("eras");
        for (JsonElement e : eras) {
            JsonObject era = e.getAsJsonObject();
            List<String> patterns = new ArrayList<>();
            for (JsonElement p : era.getAsJsonArray("mcPatterns")) patterns.add(p.getAsString());
            result.add(new Era(
                    era.get("name").getAsString(),
                    patterns,
                    era.get("runtimeMappings").getAsString(),
                    era.get("support").getAsString(),
                    era.get("summary").getAsString()
            ));
        }
        return result;
    }

    /**
     * Finds the era matching the mod's declared target Minecraft. Falls back to
     * the ecosystem's last era (the most conservative) when the target is
     * undeclared and no wildcard era matched.
     */
    public Optional<Era> eraFor(DetectedMod mod) {
        List<Era> candidates = eras.get(mod.ecosystem());
        if (candidates == null || candidates.isEmpty()) return Optional.empty();
        List<String> tokens = McVersions.extractTokens(mod.targetMinecraft());
        for (Era era : candidates) {
            if (era.matches(tokens)) return Optional.of(era);
        }
        return Optional.of(candidates.getLast());
    }
}
