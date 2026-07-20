package studios.milkdromeda.octoloader.knowledge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The ordered timeline of documented Minecraft versions, from Beta 1.7.3 up to
 * the current release, shipped as {@code octoloader/knowledge/minecraft_versions.json}.
 * Every adjacent pair in the timeline is one {@link ApiStep} in the API
 * documentation chain; the index answers "which documented version is this
 * token?" and "which steps lie between these two versions?".
 */
public final class McVersionIndex {
    private final List<Version> versions;
    private final Map<String, Integer> lookup = new HashMap<>();

    /**
     * @param id      canonical version id ({@code "b1.7.3"}, {@code "1.12.2"}, {@code "26.1"})
     * @param era     {@code "beta"}, {@code "obfuscated"} or {@code "unobfuscated"}
     * @param aliases other version strings that resolve to this entry (e.g. the
     *                in-family releases {@code "1.12"}, {@code "1.12.1"})
     */
    public record Version(String id, String era, List<String> aliases) {
    }

    private McVersionIndex(List<Version> versions) {
        this.versions = List.copyOf(versions);
        for (int i = 0; i < versions.size(); i++) {
            Version v = versions.get(i);
            lookup.put(v.id(), i);
            for (String alias : v.aliases()) lookup.putIfAbsent(alias, i);
        }
    }

    public static McVersionIndex parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<Version> versions = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("versions")) {
            JsonObject v = e.getAsJsonObject();
            List<String> aliases = new ArrayList<>();
            JsonArray aliasArray = v.getAsJsonArray("aliases");
            if (aliasArray != null) {
                for (JsonElement a : aliasArray) aliases.add(a.getAsString());
            }
            versions.add(new Version(v.get("id").getAsString(), v.get("era").getAsString(), aliases));
        }
        if (versions.isEmpty()) throw new IllegalStateException("version index is empty");
        return new McVersionIndex(versions);
    }

    /** All version ids, oldest first. */
    public List<String> ids() {
        return versions.stream().map(Version::id).toList();
    }

    public String oldest() {
        return versions.getFirst().id();
    }

    public String newest() {
        return versions.getLast().id();
    }

    /** Resolves a version token to its canonical documented id, if known. */
    public Optional<String> resolve(String token) {
        if (token == null) return Optional.empty();
        Integer i = lookup.get(token.trim());
        return i == null ? Optional.empty() : Optional.of(versions.get(i).id());
    }

    /** Timeline order of two canonical ids: negative if {@code a} is older than {@code b}. */
    public int compare(String idA, String idB) {
        return position(idA) - position(idB);
    }

    /**
     * The canonical ids of every step start between {@code fromId} (inclusive)
     * and {@code toId} (exclusive) — each returned id names one documented
     * step to its successor.
     */
    public List<String> stepStartsBetween(String fromId, String toId) {
        int from = position(fromId);
        int to = position(toId);
        if (from > to) throw new IllegalArgumentException(fromId + " is newer than " + toId);
        List<String> result = new ArrayList<>();
        for (int i = from; i < to; i++) result.add(versions.get(i).id());
        return result;
    }

    /** The documented version immediately after {@code id}, if any. */
    public Optional<String> successor(String id) {
        int i = position(id);
        return i + 1 < versions.size() ? Optional.of(versions.get(i + 1).id()) : Optional.empty();
    }

    private int position(String id) {
        Integer i = lookup.get(id);
        if (i == null) throw new IllegalArgumentException("unknown version id " + id);
        return i;
    }
}
