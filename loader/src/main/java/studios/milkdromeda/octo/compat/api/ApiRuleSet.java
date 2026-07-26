package studios.milkdromeda.octo.compat.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import studios.milkdromeda.octo.compat.Era;
import studios.milkdromeda.octo.compat.mapping.MappingSet;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * The rules for carrying a mod across an API change.
 *
 * <p>Packs are JSON, keyed by the pair of eras they bridge, and are loaded both
 * from inside the loader jar and from {@code <gameDir>/.octo/api}, so a player
 * can teach Octo about a mod nobody has handled yet without rebuilding it.
 *
 * <pre>
 * {
 *   "from": "searge", "to": "current",
 *   "methods": [
 *     { "owner": "net/minecraft/client/Minecraft", "name": "getMinecraft",
 *       "newName": "getInstance", "note": "renamed in 1.13" }
 *   ]
 * }
 * </pre>
 */
public final class ApiRuleSet {
    private static final OctoLog LOG = OctoLog.of(ApiRuleSet.class);
    public static final ApiRuleSet EMPTY = new ApiRuleSet(List.of(), Map.of());

    private final List<ApiRule> rules;
    private final Map<String, String> classRenames;

    private ApiRuleSet(List<ApiRule> rules, Map<String, String> classRenames) {
        this.rules = List.copyOf(rules);
        this.classRenames = Map.copyOf(classRenames);
    }

    public List<ApiRule> rules() {
        return rules;
    }

    public boolean isEmpty() {
        return rules.isEmpty() && classRenames.isEmpty();
    }

    public int size() {
        return rules.size() + classRenames.size();
    }

    /** Class-level renames, expressed as a mapping set so they compose with real mappings. */
    public MappingSet classMappings() {
        MappingSet.Builder builder = MappingSet.builder("api-class-renames");
        classRenames.forEach(builder::mapClass);
        return builder.build();
    }

    public ApiRule findMethod(String owner, String name, String descriptor) {
        for (ApiRule rule : rules) {
            if (rule.kind() != ApiRule.Kind.CLASS_MOVED && rule.matchesMethod(owner, name, descriptor)) {
                return rule;
            }
        }

        return null;
    }

    public ApiRule findField(String owner, String name, String descriptor) {
        for (ApiRule rule : rules) {
            if ((rule.kind() == ApiRule.Kind.FIELD_TO_METHOD || rule.kind() == ApiRule.Kind.REMOVED)
                    && rule.matchesField(owner, name, descriptor)) {
                return rule;
            }
        }

        return null;
    }

    public ApiRuleSet merge(ApiRuleSet other) {
        if (other.isEmpty()) {
            return this;
        }

        if (isEmpty()) {
            return other;
        }

        List<ApiRule> merged = new ArrayList<>(rules);
        merged.addAll(other.rules);
        Map<String, String> mergedClasses = new LinkedHashMap<>(classRenames);
        mergedClasses.putAll(other.classRenames);
        return new ApiRuleSet(merged, mergedClasses);
    }

    /**
     * Collects every pack that applies when moving from one era to another.
     *
     * <p>Packs are additive: a mod from the Searge years crossing into the
     * current era picks up the searge-to-flattening pack, the flattening-to-modern
     * pack, and so on, in order.
     */
    public static ApiRuleSet forEras(Era from, Era to, Path userDirectory) {
        if (!from.isKnown() || !to.isKnown() || !from.isOlderThan(to)) {
            return EMPTY;
        }

        ApiRuleSet combined = EMPTY;
        Era[] eras = Era.values();

        for (int i = from.ordinal(); i < to.ordinal(); i++) {
            Era step = eras[i];
            Era next = eras[i + 1];
            combined = combined.merge(loadPack(step, next, userDirectory));
        }

        // A pack naming the whole span wins over the step-by-step route, since it
        // was written with the destination in mind.
        return combined.merge(loadPack(from, to, userDirectory));
    }

    private static ApiRuleSet loadPack(Era from, Era to, Path userDirectory) {
        String fileName = from.id() + "-to-" + to.id() + ".json";
        ApiRuleSet bundled = EMPTY;

        try (InputStream in = ApiRuleSet.class.getResourceAsStream("/octo/api/" + fileName)) {
            if (in != null) {
                bundled = parse(new InputStreamReader(in, StandardCharsets.UTF_8), fileName);
            }
        } catch (IOException e) {
            LOG.warn("could not read bundled rule pack {}: {}", fileName, e.toString());
        }

        if (userDirectory == null) {
            return bundled;
        }

        Path userPack = userDirectory.resolve(fileName);

        if (!Files.isRegularFile(userPack)) {
            return bundled;
        }

        try (var reader = Files.newBufferedReader(userPack, StandardCharsets.UTF_8)) {
            return bundled.merge(parse(reader, userPack.toString()));
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not read rule pack {}: {}", userPack, e.toString());
            return bundled;
        }
    }

    /** Loads every user-supplied pack, whatever eras it names. */
    public static ApiRuleSet loadAll(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return EMPTY;
        }

        ApiRuleSet combined = EMPTY;

        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    combined = combined.merge(parse(reader, file.toString()));
                } catch (IOException | RuntimeException e) {
                    LOG.warn("could not read rule pack {}: {}", file, e.toString());
                }
            }
        } catch (IOException e) {
            LOG.warn("could not list rule packs in {}: {}", directory, e.toString());
        }

        return combined;
    }

    /** Parses one pack. Public so a rule pack can be validated without installing it. */
    public static ApiRuleSet parse(java.io.Reader source, String origin) {
        JsonElement root = JsonParser.parseReader(source);

        if (!root.isJsonObject()) {
            LOG.warn("rule pack {} is not an object", origin);
            return EMPTY;
        }

        JsonObject json = root.getAsJsonObject();
        List<ApiRule> rules = new ArrayList<>();
        Map<String, String> classRenames = new LinkedHashMap<>();

        for (JsonElement element : array(json, "classes")) {
            JsonObject entry = element.getAsJsonObject();
            String from = string(entry, "from");
            String to = string(entry, "to");

            if (from != null && to != null) {
                classRenames.put(from, to);
                rules.add(new ApiRule(ApiRule.Kind.CLASS_MOVED, from, "", null, to, "", null,
                        string(entry, "note", from + " became " + to)));
            }
        }

        for (JsonElement element : array(json, "methods")) {
            JsonObject entry = element.getAsJsonObject();
            ApiRule rule = readMember(entry, defaultKind(entry, ApiRule.Kind.METHOD_MOVED));

            if (rule != null) {
                rules.add(rule);
            }
        }

        for (JsonElement element : array(json, "fields")) {
            JsonObject entry = element.getAsJsonObject();
            ApiRule rule = readMember(entry, defaultKind(entry, ApiRule.Kind.FIELD_TO_METHOD));

            if (rule != null) {
                rules.add(rule);
            }
        }

        for (JsonElement element : array(json, "removed")) {
            JsonObject entry = element.getAsJsonObject();
            ApiRule rule = readMember(entry, ApiRule.Kind.REMOVED);

            if (rule != null) {
                rules.add(rule);
            }
        }

        LOG.debug("rule pack {}: {} rules, {} class renames", origin, rules.size(), classRenames.size());
        return new ApiRuleSet(rules, classRenames);
    }

    private static ApiRule.Kind defaultKind(JsonObject entry, ApiRule.Kind fallback) {
        String declared = string(entry, "kind");

        if (declared == null) {
            return fallback;
        }

        try {
            return ApiRule.Kind.valueOf(declared.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static ApiRule readMember(JsonObject entry, ApiRule.Kind kind) {
        String owner = string(entry, "owner");
        String name = string(entry, "name");

        if (owner == null || name == null) {
            return null;
        }

        return new ApiRule(kind, owner, name, string(entry, "desc"),
                string(entry, "newOwner", owner), string(entry, "newName", name),
                string(entry, "newDesc"), string(entry, "note", ""));
    }

    private static List<JsonElement> array(JsonObject json, String key) {
        List<JsonElement> out = new ArrayList<>();
        JsonElement element = json.get(key);

        if (element != null && element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (child.isJsonObject()) {
                    out.add(child);
                }
            }
        }

        return out;
    }

    private static String string(JsonObject json, String key) {
        return string(json, key, null);
    }

    private static String string(JsonObject json, String key, String fallback) {
        JsonElement element = json.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : fallback;
    }
}
