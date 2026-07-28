package studios.milkdromeda.octo.compat.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import studios.milkdromeda.octo.util.OctoLog;

/**
 * What this version of the game has instead.
 *
 * <p>{@link ApiRuleSet} answers "how do I carry a mod forward from the era it
 * was built in", which needs to know both eras. This answers a narrower and much
 * more common question: a mod names a class or a member, and the runtime the
 * player happens to be on does not have it — under that name, in that package,
 * with that spelling. Minecraft moves a class every few versions and mods are
 * built against one of them, so the same Create build is missing
 * {@code LayeredDraw$Layer} on 1.20.1 and finds it on 1.21.
 *
 * <p>Every entry is a list of candidates, tried in order, and <em>nothing
 * happens unless the original is genuinely absent from the running game</em>.
 * That is what makes one table correct across all versions rather than one table
 * per version: on a runtime that has the class, the table is inert.
 *
 * <p>When no candidate resolves either, an entry may declare that a stand-in is
 * acceptable. That declaration is the only thing that lets Octo fabricate a
 * {@code net.minecraft.*} class — the loader will invent a missing piece of a
 * loader API on its own, because that gap is Octo's fault, but inventing a piece
 * of Minecraft is a decision someone has to have written down.
 *
 * <pre>
 * {
 *   "classes": [
 *     { "name": "net/minecraft/client/gui/LayeredDraw$Layer",
 *       "alternatives": ["net/minecraft/client/gui/LayeredDrawer$Layer"],
 *       "stub": "interface",
 *       "note": "the HUD layer type arrived in 1.20.5" }
 *   ],
 *   "methods": [
 *     { "owner": "net/minecraft/SystemReport", "name": "setDetail",
 *       "alternatives": ["setDetail", "func_230486_a_"] }
 *   ]
 * }
 * </pre>
 */
public final class Equivalents {
    private static final OctoLog LOG = OctoLog.of(Equivalents.class);

    /** The bundled table, and the name a player's own additions must use. */
    public static final String FILE_NAME = "equivalents.json";

    public static final Equivalents EMPTY = new Equivalents(Map.of(), Map.of(), Map.of());

    /** Whether a class with no surviving equivalent may be fabricated, and as what. */
    public enum Stub {
        /** Do not invent anything; the mod fails as it would have without Octo. */
        NONE,
        /** Declare it as an interface — right for anything a mod only implements. */
        INTERFACE,
        /** Declare it as a class — right for anything a mod extends or constructs. */
        CLASS;

        static Stub parse(String raw, Stub fallback) {
            if (raw == null) {
                return fallback;
            }

            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return fallback;
            }
        }
    }

    /**
     * @param name         the internal name a mod compiled against
     * @param alternatives the names to try instead, in order of preference
     * @param stub         what to fabricate when none of them exists either
     */
    public record ClassEntry(String name, List<String> alternatives, Stub stub, String note) {
    }

    /**
     * @param owner        the class the member is looked for on, or {@code *} for any
     * @param alternatives the names to try instead, in order of preference
     */
    public record MemberEntry(String owner, String name, List<String> alternatives, String note) {
    }

    private final Map<String, ClassEntry> classes;
    private final Map<String, MemberEntry> methods;
    private final Map<String, MemberEntry> fields;

    private Equivalents(Map<String, ClassEntry> classes, Map<String, MemberEntry> methods,
            Map<String, MemberEntry> fields) {
        this.classes = Map.copyOf(classes);
        this.methods = Map.copyOf(methods);
        this.fields = Map.copyOf(fields);
    }

    public boolean isEmpty() {
        return classes.isEmpty() && methods.isEmpty() && fields.isEmpty();
    }

    public int size() {
        return classes.size() + methods.size() + fields.size();
    }

    public java.util.Collection<ClassEntry> classes() {
        return classes.values();
    }

    /** @return what to do about a class name, or {@code null} when nothing is known about it */
    public ClassEntry classEntry(String internalName) {
        return classes.get(internalName);
    }

    /** Every class name the table mentions, for a cheap "could this class file care" check. */
    public Set<String> mentionedClasses() {
        return classes.keySet();
    }

    /**
     * @return the names to try for a method, most preferred first, or empty when
     *         the table says nothing about it
     */
    public List<String> methodAliases(String owner, String name) {
        return aliases(methods, owner, name);
    }

    /** @return the names to try for a field, most preferred first */
    public List<String> fieldAliases(String owner, String name) {
        return aliases(fields, owner, name);
    }

    private static List<String> aliases(Map<String, MemberEntry> table, String owner, String name) {
        MemberEntry entry = table.get(key(owner, name));

        if (entry == null) {
            entry = table.get(key("*", name));
        }

        return entry == null ? List.of() : entry.alternatives();
    }

    private static String key(String owner, String name) {
        return owner + "." + name;
    }

    public Equivalents merge(Equivalents other) {
        if (other.isEmpty()) {
            return this;
        }

        if (isEmpty()) {
            return other;
        }

        Map<String, ClassEntry> mergedClasses = new LinkedHashMap<>(classes);
        mergedClasses.putAll(other.classes);
        Map<String, MemberEntry> mergedMethods = new LinkedHashMap<>(methods);
        mergedMethods.putAll(other.methods);
        Map<String, MemberEntry> mergedFields = new LinkedHashMap<>(fields);
        mergedFields.putAll(other.fields);
        return new Equivalents(mergedClasses, mergedMethods, mergedFields);
    }

    /**
     * The bundled table, plus anything the player dropped in {@code .octo/api}.
     *
     * <p>A player's file wins over the bundled one entry by entry, which is the
     * whole point: a mod nobody has handled yet can be made to load without
     * waiting for a new Octo.
     */
    public static Equivalents load(Path userDirectory) {
        Equivalents combined = EMPTY;

        try (InputStream in = Equivalents.class.getResourceAsStream("/octo/api/" + FILE_NAME)) {
            if (in != null) {
                combined = parse(new InputStreamReader(in, StandardCharsets.UTF_8), "bundled " + FILE_NAME);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not read the bundled equivalents table: {}", e.toString());
        }

        if (userDirectory == null) {
            return combined;
        }

        Path userTable = userDirectory.resolve(FILE_NAME);

        if (!Files.isRegularFile(userTable)) {
            return combined;
        }

        try (var reader = Files.newBufferedReader(userTable, StandardCharsets.UTF_8)) {
            return combined.merge(parse(reader, userTable.toString()));
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not read {}: {}", userTable, e.toString());
            return combined;
        }
    }

    /** Parses one table. Public so a table can be checked without installing it. */
    public static Equivalents parse(java.io.Reader source, String origin) {
        JsonElement root = JsonParser.parseReader(source);

        if (!root.isJsonObject()) {
            LOG.warn("equivalents table {} is not an object", origin);
            return EMPTY;
        }

        JsonObject json = root.getAsJsonObject();
        Map<String, ClassEntry> classes = new LinkedHashMap<>();
        Map<String, MemberEntry> methods = new LinkedHashMap<>();
        Map<String, MemberEntry> fields = new LinkedHashMap<>();

        for (JsonObject entry : array(json, "classes")) {
            String name = string(entry, "name");

            if (name == null) {
                continue;
            }

            classes.put(name, new ClassEntry(name, strings(entry, "alternatives", name),
                    Stub.parse(string(entry, "stub"), Stub.NONE), string(entry, "note", "")));
        }

        readMembers(json, "methods", methods);
        readMembers(json, "fields", fields);

        LOG.debug("equivalents table {}: {} class(es), {} method(s), {} field(s)", origin,
                classes.size(), methods.size(), fields.size());
        return new Equivalents(classes, methods, fields);
    }

    private static void readMembers(JsonObject json, String key, Map<String, MemberEntry> into) {
        for (JsonObject entry : array(json, key)) {
            String name = string(entry, "name");

            if (name == null) {
                continue;
            }

            String owner = string(entry, "owner", "*");
            into.put(key(owner, name),
                    new MemberEntry(owner, name, strings(entry, "alternatives", name), string(entry, "note", "")));
        }
    }

    /**
     * @param exclude the name being replaced; an entry that lists itself among its
     *                own alternatives would otherwise send the resolver back to
     *                the name it already knows is missing
     */
    private static List<String> strings(JsonObject json, String key, String exclude) {
        JsonElement element = json.get(key);

        if (element == null || !element.isJsonArray()) {
            return List.of();
        }

        Set<String> out = new LinkedHashSet<>();

        for (JsonElement child : element.getAsJsonArray()) {
            if (child.isJsonPrimitive()) {
                String value = child.getAsString();

                if (!value.isBlank() && !value.equals(exclude)) {
                    out.add(value);
                }
            }
        }

        return List.copyOf(out);
    }

    private static List<JsonObject> array(JsonObject json, String key) {
        List<JsonObject> out = new ArrayList<>();
        JsonElement element = json.get(key);

        if (element != null && element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                if (child.isJsonObject()) {
                    out.add(child.getAsJsonObject());
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
