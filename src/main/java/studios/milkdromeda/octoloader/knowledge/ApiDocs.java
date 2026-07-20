package studios.milkdromeda.octoloader.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The per-version API documentation Octo Loader ships with: for every version
 * in the {@link McVersionIndex} timeline, one JSON resource under
 * {@code octoloader/knowledge/apidocs/<version>.json} documenting what changed
 * between that version and its successor. Together the steps form a chain from
 * Beta 1.7.3 to the current release; the replacement engine composes them to
 * carry a mod's compiled API references forward wherever the chain is fully
 * mechanical, and to explain precisely where and why it is not.
 */
public final class ApiDocs {
    private static final String RESOURCE_DIR = "/octoloader/knowledge/apidocs/";

    private final Map<String, ApiStep> stepsByFrom;

    private ApiDocs(Map<String, ApiStep> stepsByFrom) {
        this.stepsByFrom = stepsByFrom;
    }

    /** Loads the step doc of every non-final version in the index; missing docs are simply absent. */
    public static ApiDocs load(McVersionIndex index) {
        Map<String, ApiStep> steps = new HashMap<>();
        for (String id : index.ids()) {
            if (id.equals(index.newest())) continue;
            String resource = RESOURCE_DIR + id + ".json";
            try (InputStream in = ApiDocs.class.getResourceAsStream(resource)) {
                if (in == null) continue;
                steps.put(id, parseStep(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Malformed API doc resource " + resource, e);
            }
        }
        return new ApiDocs(steps);
    }

    /** The documented step starting at the given canonical version id. */
    public Optional<ApiStep> stepFrom(String versionId) {
        return Optional.ofNullable(stepsByFrom.get(versionId));
    }

    static ApiStep parseStep(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        Map<String, String> classRenames = new LinkedHashMap<>();
        JsonObject renames = root.getAsJsonObject("classRenames");
        if (renames != null) {
            for (Map.Entry<String, JsonElement> e : renames.entrySet()) {
                classRenames.put(e.getKey(), e.getValue().getAsString());
            }
        }

        List<ApiStep.MemberRename> memberRenames = new ArrayList<>();
        JsonArray members = root.getAsJsonArray("memberRenames");
        if (members != null) {
            for (JsonElement e : members) {
                JsonObject m = e.getAsJsonObject();
                memberRenames.add(new ApiStep.MemberRename(
                        m.get("kind").getAsString(),
                        m.get("owner").getAsString(),
                        m.get("name").getAsString(),
                        optString(m, "desc"),
                        m.get("newName").getAsString()
                ));
            }
        }

        List<ApiStep.RemovedApi> removedApis = new ArrayList<>();
        JsonArray removed = root.getAsJsonArray("removedApis");
        if (removed != null) {
            for (JsonElement e : removed) {
                JsonObject r = e.getAsJsonObject();
                removedApis.add(new ApiStep.RemovedApi(
                        r.get("api").getAsString(),
                        optString(r, "replacement"),
                        optString(r, "note")
                ));
            }
        }

        List<String> notableChanges = new ArrayList<>();
        JsonArray notable = root.getAsJsonArray("notableChanges");
        if (notable != null) {
            for (JsonElement e : notable) notableChanges.add(e.getAsString());
        }

        return new ApiStep(
                root.get("from").getAsString(),
                root.get("to").getAsString(),
                root.get("coverage").getAsString(),
                root.get("runtimeMappings").getAsString(),
                root.get("summary").getAsString(),
                classRenames,
                memberRenames,
                removedApis,
                notableChanges
        );
    }

    private static String optString(JsonObject obj, String key) {
        JsonElement value = obj.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
