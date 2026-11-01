package studios.milkdromeda.octo.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What a world was last opened with: the mods and their versions, and every
 * block, item and entity id those mods added. Kept in
 * {@code <world>/octo/content.json}.
 *
 * <p>Compared with what the game has now, it tells a world that is safe to
 * open from one that is about to lose blocks: a mod that failed to build, was
 * removed, or renamed its blocks leaves ids the world uses that the game no
 * longer knows, and the game would quietly save air in their place.
 */
public final class WorldContent {
    public static final String FILE = "octo/content.json";
    /** Mods the player has said are gone for good: their content is not waited for any more. */
    public static final String ACCEPTED_FILE = "octo/accepted-removals.txt";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Map<String, String> mods;
    private final Map<String, Set<String>> ids;

    public WorldContent(Map<String, String> mods, Map<String, ? extends Collection<String>> ids) {
        this.mods = new TreeMap<>(mods);
        this.ids = new TreeMap<>();
        ids.forEach((registry, values) -> this.ids.put(registry, new TreeSet<>(values)));
    }

    public Map<String, String> mods() {
        return mods;
    }

    public Map<String, Set<String>> ids() {
        return ids;
    }

    /** What changed between this record and what the game has now. */
    public record Difference(Set<String> added, Set<String> removed, Map<String, String[]> updated,
            Map<String, Set<String>> missing) {
        public boolean modsChanged() {
            return !added.isEmpty() || !removed.isEmpty() || !updated.isEmpty();
        }

        public boolean contentMissing() {
            return !missing.isEmpty();
        }

        /** The mods whose content is missing, by the namespace of the ids. */
        public Set<String> missingMods() {
            Set<String> out = new TreeSet<>();
            missing.values().forEach(values -> values.forEach(id -> out.add(namespace(id))));
            return out;
        }

        public int missingCount() {
            return missing.values().stream().mapToInt(Set::size).sum();
        }
    }

    /**
     * @param accepted mods whose content is not to be counted missing
     */
    public Difference compareTo(WorldContent now, Set<String> accepted) {
        Set<String> added = new TreeSet<>(now.mods.keySet());
        added.removeAll(mods.keySet());
        Set<String> removed = new TreeSet<>(mods.keySet());
        removed.removeAll(now.mods.keySet());
        Map<String, String[]> updated = new TreeMap<>();

        mods.forEach((id, version) -> {
            String current = now.mods.get(id);

            if (current != null && !current.equals(version)) {
                updated.put(id, new String[] { version, current });
            }
        });

        Map<String, Set<String>> missing = new TreeMap<>();

        ids.forEach((registry, values) -> {
            Set<String> present = now.ids.getOrDefault(registry, Set.of());
            Set<String> gone = new TreeSet<>();

            for (String id : values) {
                if (!present.contains(id) && !accepted.contains(namespace(id))) {
                    gone.add(id);
                }
            }

            if (!gone.isEmpty()) {
                missing.put(registry, gone);
            }
        });

        return new Difference(added, removed, updated, missing);
    }

    /**
     * What to record after a world was opened: what the game has now, and
     * still everything that was missing. A world keeps a mod's blocks while
     * the mod is away, so it must go on knowing it needs them.
     */
    public WorldContent carryingOver(Difference difference) {
        Map<String, Set<String>> merged = new TreeMap<>();
        ids.forEach((registry, values) -> merged.put(registry, new TreeSet<>(values)));
        difference.missing().forEach((registry, values) ->
                merged.computeIfAbsent(registry, ignored -> new TreeSet<>()).addAll(values));
        Map<String, String> allMods = new TreeMap<>(mods);
        return new WorldContent(allMods, merged);
    }

    public static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    // ------------------------------------------------------------------ files

    /** The world's record, or {@code null} if it has none yet. */
    public static WorldContent read(Path world) {
        Path file = world.resolve(FILE);

        if (!Files.isRegularFile(file)) {
            return null;
        }

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JsonObject root = parsed.getAsJsonObject();
            Map<String, String> mods = new TreeMap<>();
            Map<String, List<String>> ids = new TreeMap<>();

            if (root.get("mods") instanceof JsonObject modsObject) {
                modsObject.entrySet().forEach(entry -> mods.put(entry.getKey(), entry.getValue().getAsString()));
            }

            if (root.get("ids") instanceof JsonObject idsObject) {
                idsObject.entrySet().forEach(entry -> {
                    List<String> values = new ArrayList<>();
                    entry.getValue().getAsJsonArray().forEach(value -> values.add(value.getAsString()));
                    ids.put(entry.getKey(), values);
                });
            }

            return new WorldContent(mods, ids);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public void write(Path world) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("comment", "Written by Octo Loader: what this world was last opened with. Octo backs the "
                + "world up and keeps chunks it cannot read unchanged when a mod listed here goes missing.");
        JsonObject modsObject = new JsonObject();
        mods.forEach(modsObject::addProperty);
        root.add("mods", modsObject);
        JsonObject idsObject = new JsonObject();

        ids.forEach((registry, values) -> {
            JsonArray array = new JsonArray();
            values.forEach(array::add);
            idsObject.add(registry, array);
        });

        root.add("ids", idsObject);
        Path file = world.resolve(FILE);
        Files.createDirectories(file.getParent());
        Path part = file.resolveSibling(file.getFileName() + ".part");
        Files.writeString(part, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
        Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The mods the player has accepted are gone from this world. */
    public static Set<String> accepted(Path world) {
        Set<String> out = new TreeSet<>();
        Path file = world.resolve(ACCEPTED_FILE);

        if (Files.isRegularFile(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();

                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        out.add(trimmed);
                    }
                }
            } catch (IOException e) {
                // Nothing accepted, then.
            }
        }

        return out;
    }

    /** Records that a mod is gone from this world for good: its blocks may be lost from now on. */
    public static void accept(Path world, Collection<String> modIds) throws IOException {
        Set<String> all = accepted(world);
        all.addAll(modIds);
        Path file = world.resolve(ACCEPTED_FILE);
        Files.createDirectories(file.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("# Mods removed from this world on purpose. Octo no longer protects their blocks:");
        lines.add("# the next time a chunk with them is saved, they are gone. One mod id per line.");
        lines.addAll(all);
        Files.write(file, lines, StandardCharsets.UTF_8);
    }
}
