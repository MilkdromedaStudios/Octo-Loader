package studios.milkdromeda.octo.compat.mapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import studios.milkdromeda.octo.compat.Era;
import studios.milkdromeda.octo.compat.Era.MappingNamespace;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Holds every mapping table available, and finds routes between them.
 *
 * <p>No single file maps a 2013 mod onto a 2025 runtime. What exists instead is
 * a set of hops — MCP to obfuscated, obfuscated to intermediary, intermediary to
 * Mojang's names — published by different projects at different times. The
 * registry treats those as edges of a graph and finds the shortest path between
 * the namespace a mod is written in and the one the game is running in.
 *
 * <p>Files are read from {@code <gameDir>/.octo/mappings}, named
 * {@code <from>-to-<to>.<ext>}, for example {@code searge-to-official.tsrg} or
 * {@code intermediary-to-mojang.tiny}. Any format in {@link MappingReaders} works.
 */
public final class MappingRegistry {
    private static final OctoLog LOG = OctoLog.of(MappingRegistry.class);
    private static final MappingRegistry EMPTY = new MappingRegistry(Map.of(), "official");

    private static volatile MappingRegistry instance = EMPTY;

    /** from-namespace -> to-namespace -> table. */
    private final Map<String, Map<String, MappingSet>> edges;
    private final String runtimeNamespace;
    private final Map<String, MappingSet> routeCache = new HashMap<>();

    private MappingRegistry(Map<String, Map<String, MappingSet>> edges, String runtimeNamespace) {
        this.edges = edges;
        this.runtimeNamespace = runtimeNamespace;
    }

    public static MappingRegistry get() {
        return instance;
    }

    public static void install(MappingRegistry registry) {
        instance = registry;
    }

    public static void reset() {
        instance = EMPTY;
    }

    /**
     * Loads every mapping file in a directory. A missing directory is not an
     * error — it only means no cross-version translation is available, and mods
     * matching the runtime still load normally.
     */
    public static MappingRegistry load(Path directory, String runtimeNamespace) {
        Map<String, Map<String, MappingSet>> edges = new LinkedHashMap<>();

        if (directory != null && Files.isDirectory(directory)) {
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    readInto(edges, file);
                }
            } catch (IOException e) {
                LOG.warn("could not read mappings from {}: {}", directory, e.toString());
            }
        }

        MappingRegistry registry = new MappingRegistry(edges, normalise(runtimeNamespace));

        if (!edges.isEmpty()) {
            LOG.info("loaded {} mapping route(s) from {}", registry.edgeCount(), directory);
        }

        return registry;
    }

    private static void readInto(Map<String, Map<String, MappingSet>> edges, Path file) {
        String name = file.getFileName().toString();
        int separator = name.indexOf("-to-");

        if (separator < 0) {
            LOG.debug("ignoring {}: name must be <from>-to-<to>.<ext>", name);
            return;
        }

        String from = normalise(name.substring(0, separator));
        String to = normalise(name.substring(separator + 4).replaceAll("\\.[^.]+$", ""));

        try {
            MappingSet set = MappingReaders.read(file, from, to);

            if (set.isEmpty()) {
                LOG.warn("mapping file {} is empty", name);
                return;
            }

            edges.computeIfAbsent(from, ignored -> new LinkedHashMap<>()).put(to, set);
            // Every table can be walked backwards, which halves what a user has to supply.
            edges.computeIfAbsent(to, ignored -> new LinkedHashMap<>()).putIfAbsent(from, set.invert());
            LOG.info("mappings {} -> {}: {}", from, to, set);
        } catch (IOException | RuntimeException e) {
            LOG.warn("could not read mapping file {}: {}", name, e.toString());
        }
    }

    public String runtimeNamespace() {
        return runtimeNamespace;
    }

    public int edgeCount() {
        int count = 0;

        for (Map<String, MappingSet> targets : edges.values()) {
            count += targets.size();
        }

        return count;
    }

    public boolean isEmpty() {
        return edges.isEmpty();
    }

    /** The table taking a mod written for {@code from} to the running game, or empty when none exists. */
    public MappingSet routeFor(Era from) {
        return route(namespaceOf(from), runtimeNamespace);
    }

    public MappingSet route(String fromNamespace, String toNamespace) {
        String from = normalise(fromNamespace);
        String to = normalise(toNamespace);

        if (from.equals(to)) {
            return MappingSet.EMPTY;
        }

        String key = from + "->" + to;

        synchronized (routeCache) {
            MappingSet cached = routeCache.get(key);

            if (cached != null) {
                return cached;
            }

            MappingSet found = search(from, to);
            routeCache.put(key, found);
            return found;
        }
    }

    /** Breadth-first search, so the route with the fewest hops wins. */
    private MappingSet search(String from, String to) {
        if (!edges.containsKey(from)) {
            return MappingSet.EMPTY;
        }

        Deque<List<String>> queue = new ArrayDeque<>();
        Set<String> seen = new HashSet<>();
        queue.add(List.of(from));
        seen.add(from);

        while (!queue.isEmpty()) {
            List<String> path = queue.poll();
            String tail = path.get(path.size() - 1);
            Map<String, MappingSet> next = edges.getOrDefault(tail, Map.of());

            for (Map.Entry<String, MappingSet> edge : next.entrySet()) {
                if (edge.getKey().equals(to)) {
                    List<String> complete = new ArrayList<>(path);
                    complete.add(to);
                    return compose(complete);
                }

                if (seen.add(edge.getKey())) {
                    List<String> extended = new ArrayList<>(path);
                    extended.add(edge.getKey());
                    queue.add(extended);
                }
            }
        }

        LOG.debug("no mapping route from {} to {}", from, to);
        return MappingSet.EMPTY;
    }

    private MappingSet compose(List<String> path) {
        MappingSet composed = MappingSet.EMPTY;

        for (int i = 0; i + 1 < path.size(); i++) {
            MappingSet hop = edges.getOrDefault(path.get(i), Map.of()).get(path.get(i + 1));

            if (hop == null) {
                return MappingSet.EMPTY;
            }

            composed = composed.then(hop);
        }

        LOG.debug("composed mapping route {}: {}", String.join(" -> ", path), composed);
        return composed;
    }

    // ------------------------------------------------- Fabric MappingResolver

    public String mapClassName(String namespace, String className) {
        String internal = route(namespace, runtimeNamespace).mapClass(className.replace('.', '/'));
        return internal.replace('/', '.');
    }

    public String unmapClassName(String targetNamespace, String className) {
        String internal = route(runtimeNamespace, targetNamespace).mapClass(className.replace('.', '/'));
        return internal.replace('/', '.');
    }

    public String mapFieldName(String namespace, String owner, String name, String descriptor) {
        return route(namespace, runtimeNamespace).mapField(owner.replace('.', '/'), name, descriptor);
    }

    public String mapMethodName(String namespace, String owner, String name, String descriptor) {
        return route(namespace, runtimeNamespace).mapMethod(owner.replace('.', '/'), name, descriptor);
    }

    public static String namespaceOf(Era era) {
        return namespaceOf(era.namespace());
    }

    public static String namespaceOf(MappingNamespace namespace) {
        return switch (namespace) {
            case SEARGE -> "searge";
            case INTERMEDIARY -> "intermediary";
            case MOJANG -> "mojang";
            case OFFICIAL, UNKNOWN -> "official";
        };
    }

    private static String normalise(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return "official";
        }

        String text = namespace.trim().toLowerCase(Locale.ROOT);

        return switch (text) {
            case "obf", "notch", "obfuscated" -> "official";
            case "srg", "mcp" -> "searge";
            case "named", "yarn", "deobf" -> "named";
            case "moj", "mojmap", "official-mojang" -> "mojang";
            default -> text;
        };
    }
}
