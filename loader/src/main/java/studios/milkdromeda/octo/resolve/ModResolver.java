package studios.milkdromeda.octo.resolve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import studios.milkdromeda.octo.launch.LaunchContext;
import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.ModDependency;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.mod.version.Version;
import studios.milkdromeda.octo.runtime.BuiltinMods;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Decides which mods load, and in which order.
 *
 * <p>Octo's resolver differs from the four it replaces in one deliberate way:
 * a dependency on a specific Minecraft version is advisory. Nearly every mod
 * ever published declares a narrow game version, and enforcing that is what
 * makes an old mod "incompatible" even when nothing it actually calls has
 * changed. So an unsatisfied game-version bound becomes a warning, the mod is
 * handed to the translation layer, and the loader finds out for real. Bounds
 * between mods are still enforced: those describe APIs the loader cannot fix.
 */
public final class ModResolver {
    private static final OctoLog LOG = OctoLog.of(ModResolver.class);

    private final LaunchContext context;

    public ModResolver(LaunchContext context) {
        this.context = context;
    }

    public Resolution resolve(List<ModCandidate> candidates) {
        Resolution resolution = new Resolution();
        Map<String, ModCandidate> selected = deduplicate(withoutBuiltins(candidates, resolution), resolution);
        Map<String, Version> provided = platformVersions();

        selected.values().removeIf(candidate -> {
            if (candidate.metadata().side().runsOn(context.side())) {
                return false;
            }

            LOG.debug("{} is {}-only, not loading on the {}", candidate.id(),
                    candidate.metadata().side(), context.side());
            return true;
        });

        for (String id : selected.keySet()) {
            provided.put(id, selected.get(id).metadata().version());
        }

        // Dropping a mod can leave others depending on it, so this settles in
        // rounds. Only the last verdict for each mod is reported, otherwise a mod
        // evaluated three times would produce the same warning three times.
        Map<String, Verdict> verdicts = new LinkedHashMap<>();
        Set<String> rejected = new HashSet<>();
        boolean changed = true;

        while (changed) {
            changed = false;

            for (ModCandidate candidate : List.copyOf(selected.values())) {
                Verdict verdict = checkDependencies(candidate, provided);
                verdicts.put(candidate.id(), verdict);

                if (!verdict.loadable()) {
                    selected.remove(candidate.id());
                    provided.remove(candidate.id());
                    rejected.add(candidate.id());
                    changed = true;
                }
            }
        }

        for (Map.Entry<String, Verdict> entry : verdicts.entrySet()) {
            Verdict verdict = entry.getValue();
            verdict.problems().forEach(problem ->
                    resolution.problem(entry.getKey(), problem.message(), problem.fatal(), problem.kind()));

            ModCandidate candidate = selected.get(entry.getKey());

            if (candidate != null) {
                verdict.notes().forEach(candidate::note);
            }
        }

        for (ModCandidate candidate : sort(selected, resolution)) {
            resolution.add(candidate);
        }

        return resolution;
    }

    /**
     * Drops jars claiming to be the game, the JVM or the loader.
     *
     * <p>Mods ship MixinExtras inside themselves as a Fabric mod with the id
     * {@code mixinextras}, often an old one. Its classes could never win anyway —
     * the loader's copy is the one every mod links against — so letting its
     * version stand in for the loader's only made a mod asking for
     * {@code mixinextras >=0.5.5} fail against a 0.4 nobody is using.
     */
    private static List<ModCandidate> withoutBuiltins(List<ModCandidate> candidates, Resolution resolution) {
        List<ModCandidate> out = new ArrayList<>(candidates.size());

        for (ModCandidate candidate : candidates) {
            if (BuiltinMods.IDS.contains(candidate.id())) {
                LOG.debug("{} from {} is provided by the loader itself; using the loader's",
                        candidate.id(), candidate.path().getFileName());
                continue;
            }

            out.add(candidate);
        }

        return out;
    }

    /** Keeps the highest version of each mod id. */
    private Map<String, ModCandidate> deduplicate(List<ModCandidate> candidates, Resolution resolution) {
        Map<String, ModCandidate> selected = new LinkedHashMap<>();

        for (ModCandidate candidate : candidates) {
            ModCandidate existing = selected.get(candidate.id());

            if (existing == null) {
                selected.put(candidate.id(), candidate);
                continue;
            }

            ModCandidate winner = candidate.metadata().version().compareTo(existing.metadata().version()) > 0
                    ? candidate : existing;
            ModCandidate loser = winner == candidate ? existing : candidate;

            selected.put(candidate.id(), winner);
            resolution.problem(candidate.id(), "found more than once; using "
                    + winner.metadata().version() + " from " + winner.path().getFileName()
                    + " and ignoring " + loser.metadata().version() + " from " + loser.path().getFileName(),
                    false, Resolution.Kind.DUPLICATE);
        }

        return selected;
    }

    /**
     * What Octo claims to be when a mod asks for a loader by name.
     *
     * <p>These are not decoration. Every current Fabric API module declares
     * {@code fabricloader >=0.18.4}, so reporting an older number turns each one
     * into a warning at startup, and a mod that gates a feature on the loader
     * version takes the wrong branch. The numbers track the newest release of
     * each loader that Octo's mod-facing surface matches; the bound is advisory
     * either way, but it should be advisory and right rather than advisory and
     * wrong.
     */
    private static final String FABRIC_LOADER_VERSION = BuiltinMods.FABRIC_LOADER_VERSION;
    private static final String QUILT_LOADER_VERSION = "0.31.0";

    /** The pseudo-mods every ecosystem expects to exist. */
    private Map<String, Version> platformVersions() {
        Map<String, Version> provided = new HashMap<>();
        Version minecraft = Version.parse(context.minecraftVersion());

        provided.put("minecraft", minecraft);
        provided.put("java", Version.parse(String.valueOf(Runtime.version().feature())));
        provided.put("octoloader", Version.parse(OctoRuntime.VERSION));
        provided.put("mixinextras", Version.parse(BuiltinMods.MIXINEXTRAS_VERSION));

        // Octo answers for all four loaders, so mods asking for any of them find
        // it. The versions reported are the ones each ecosystem shipped for this
        // game version, because mods range-check them.
        provided.put("fabricloader", Version.parse(FABRIC_LOADER_VERSION));
        provided.put("fabric", minecraft);
        provided.put("fabric-api", minecraft);
        provided.put("quilt_loader", Version.parse(QUILT_LOADER_VERSION));
        provided.put("quilt_base", minecraft);
        provided.put("forge", forgeVersionFor(minecraft));
        provided.put("neoforge", neoForgeVersionFor(minecraft));

        return provided;
    }

    /**
     * Forge's major version for each Minecraft release it shipped for.
     *
     * <p>Read off Forge's own Maven metadata. The year-numbered releases from 26.1
     * on keep counting up by one per game version, and a game newer than the
     * table is assumed to do the same.
     */
    private static final Map<String, Integer> FORGE_MAJOR = Map.ofEntries(
            Map.entry("1.7.10", 10), Map.entry("1.8", 11), Map.entry("1.8.9", 11), Map.entry("1.10", 12),
            Map.entry("1.10.2", 12), Map.entry("1.11", 13), Map.entry("1.11.2", 13), Map.entry("1.12", 14),
            Map.entry("1.12.1", 14), Map.entry("1.12.2", 14), Map.entry("1.13.2", 25), Map.entry("1.14.2", 26),
            Map.entry("1.14.3", 27), Map.entry("1.14.4", 28), Map.entry("1.15", 29), Map.entry("1.15.1", 30),
            Map.entry("1.15.2", 31), Map.entry("1.16.1", 32), Map.entry("1.16.2", 33), Map.entry("1.16.3", 34),
            Map.entry("1.16.4", 35), Map.entry("1.16.5", 36), Map.entry("1.17.1", 37), Map.entry("1.18", 38),
            Map.entry("1.18.1", 39), Map.entry("1.18.2", 40), Map.entry("1.19", 41), Map.entry("1.19.1", 42),
            Map.entry("1.19.2", 43), Map.entry("1.19.3", 44), Map.entry("1.19.4", 45), Map.entry("1.20", 46),
            Map.entry("1.20.1", 47), Map.entry("1.20.2", 48), Map.entry("1.20.4", 49), Map.entry("1.20.6", 50),
            Map.entry("1.21", 51), Map.entry("1.21.1", 52), Map.entry("1.21.3", 53), Map.entry("1.21.4", 54),
            Map.entry("1.21.5", 55), Map.entry("1.21.6", 56), Map.entry("1.21.7", 57), Map.entry("1.21.8", 58),
            Map.entry("1.21.9", 59), Map.entry("1.21.10", 60), Map.entry("1.21.11", 61), Map.entry("26.1", 62),
            Map.entry("26.1.1", 63), Map.entry("26.1.2", 64), Map.entry("26.2", 65), Map.entry("26.3", 66));

    /**
     * The game version a Forge major version was for, the newest when several
     * share it: 47 is 1.20.1, 14 is 1.12.2.
     *
     * @return the release, or {@code null} when the table has no such major
     */
    public static String minecraftForForge(int major) {
        Version best = null;

        for (Map.Entry<String, Integer> entry : FORGE_MAJOR.entrySet()) {
            if (entry.getValue() == major) {
                Version candidate = Version.parse(entry.getKey());

                if (best == null || candidate.compareTo(best) > 0) {
                    best = candidate;
                }
            }
        }

        return best == null ? null : best.raw();
    }

    /** Forge's version for a game version, e.g. 1.20.1 to 47.x and 26.3 to 66.x. */
    static Version forgeVersionFor(Version minecraft) {
        String release = minecraft.raw().replaceAll("[-+].*$", "");
        Integer major = FORGE_MAJOR.get(release);

        if (major == null) {
            // Not in the table: the nearest release below it, plus however many
            // feature releases separate the two. Year-numbered versions count up
            // by one per drop (26.3 -> 66, so 26.4 -> 67, 27.1 -> 68 or so).
            Version best = null;

            for (Map.Entry<String, Integer> entry : FORGE_MAJOR.entrySet()) {
                Version candidate = Version.parse(entry.getKey());

                if (candidate.compareTo(minecraft) <= 0 && (best == null || candidate.compareTo(best) > 0)) {
                    best = candidate;
                    major = entry.getValue();
                }
            }

            if (best == null) {
                major = 10;
            } else if (minecraft.part(0) >= 26) {
                int steps = (minecraft.part(0) - best.part(0)) * 4 + (minecraft.part(1) - best.part(1));
                major += Math.max(0, steps);
            }
        }

        return Version.parse(major + ".0.0");
    }

    /**
     * NeoForge's version for a game version.
     *
     * <p>Up to 1.21.x it dropped the leading {@code 1.}: 1.21.1 is 21.1.x. From
     * 26.1 on it is the game version itself with a build number after it, so
     * 26.3 is 26.3.0.x — and reporting 3.0.0 for that, as the old rule did,
     * failed every NeoForge mod's {@code [26.3,)} range.
     */
    public static Version neoForgeVersionFor(Version minecraft) {
        if (minecraft.part(0) >= 26) {
            return Version.parse(minecraft.part(0) + "." + minecraft.part(1) + "." + minecraft.part(2) + ".0");
        }

        return Version.parse(minecraft.part(1) + "." + minecraft.part(2) + ".0");
    }

    /** One evaluation of a mod's dependencies. */
    private record Verdict(boolean loadable, List<Finding> problems, List<String> notes) {
        record Finding(String message, boolean fatal, Resolution.Kind kind) {
            Finding(String message, boolean fatal) {
                this(message, fatal, Resolution.Kind.GENERAL);
            }
        }
    }

    private Verdict checkDependencies(ModCandidate candidate, Map<String, Version> provided) {
        List<Verdict.Finding> problems = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        boolean loadable = true;

        for (ModDependency dependency : candidate.metadata().dependencies()) {
            Version present = lookup(provided, dependency.modId());

            if (dependency.kind().isNegative()) {
                if (present != null && dependency.versions().test(present)) {
                    problems.add(new Verdict.Finding("declares it cannot run alongside "
                            + dependency.modId() + " " + present, true));
                    loadable = false;
                }

                continue;
            }

            if (present == null) {
                if (dependency.kind() != ModDependency.Kind.REQUIRED) {
                    continue;
                }

                if (dependency.isPlatformDependency() && context.compat().relaxVersionChecks()) {
                    // Octo provides every platform id, so this only happens for
                    // something like "fabric-api" that genuinely is not installed.
                    problems.add(new Verdict.Finding("wants " + dependency.modId()
                            + ", which is not installed; loading anyway", false));
                    notes.add("missing dependency " + dependency.modId() + " was not enforced");
                    continue;
                }

                problems.add(new Verdict.Finding("requires " + dependency.modId()
                        + " " + dependency.versions() + ", which is not installed", true));
                loadable = false;
                continue;
            }

            if (dependency.versions().test(present)) {
                continue;
            }

            boolean relaxable = context.compat().relaxVersionChecks() && dependency.isPlatformDependency();

            if (relaxable) {
                problems.add(new Verdict.Finding("was built for " + dependency.modId() + " "
                        + dependency.versions() + " but this is " + present + "; translating instead of refusing",
                        false, Resolution.Kind.PLATFORM_VERSION));
                notes.add("built for " + dependency.modId() + " " + dependency.versions()
                        + ", running on " + present);
                continue;
            }

            if (dependency.kind() == ModDependency.Kind.REQUIRED) {
                problems.add(new Verdict.Finding("requires " + dependency.modId() + " "
                        + dependency.versions() + " but " + present + " is installed", true));
                loadable = false;
            }
        }

        return new Verdict(loadable, problems, notes);
    }

    /**
     * Looks a mod id up, ignoring case as a fallback.
     *
     * <p>Mod ids only became lowercase by convention around 1.13. Before that
     * {@code Forge}, {@code CodeChickenCore} and {@code IC2} were written as the
     * author felt like, and a mod declaring {@code required-after:Forge} means
     * the same thing as one declaring {@code forge}.
     */
    private static Version lookup(Map<String, Version> provided, String modId) {
        Version exact = provided.get(modId);

        if (exact != null) {
            return exact;
        }

        String lowercase = modId.toLowerCase(java.util.Locale.ROOT);

        if (!lowercase.equals(modId)) {
            Version found = provided.get(lowercase);

            if (found != null) {
                return found;
            }
        }

        for (Map.Entry<String, Version> entry : provided.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(modId)) {
                return entry.getValue();
            }
        }

        return null;
    }

    /**
     * Orders mods so that anything a mod declared it must follow is already loaded.
     * Cycles are broken by id, which is arbitrary but stable.
     */
    private List<ModCandidate> sort(Map<String, ModCandidate> selected, Resolution resolution) {
        Map<String, Set<String>> after = new LinkedHashMap<>();

        for (ModCandidate candidate : selected.values()) {
            after.computeIfAbsent(candidate.id(), id -> new HashSet<>());

            for (ModDependency dependency : candidate.metadata().dependencies()) {
                if (!selected.containsKey(dependency.modId())) {
                    continue;
                }

                if (dependency.kind().isNegative()) {
                    continue;
                }

                switch (dependency.ordering()) {
                    case AFTER -> after.get(candidate.id()).add(dependency.modId());
                    case BEFORE -> after.computeIfAbsent(dependency.modId(), id -> new HashSet<>())
                            .add(candidate.id());
                    case NONE -> {
                        // A plain dependency still has to be constructed first.
                        if (dependency.kind() == ModDependency.Kind.REQUIRED) {
                            after.get(candidate.id()).add(dependency.modId());
                        }
                    }
                }
            }
        }

        List<ModCandidate> sorted = new ArrayList<>();
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String id : new ArrayList<>(selected.keySet())) {
            visit(id, selected, after, done, visiting, sorted, resolution);
        }

        return sorted;
    }

    private void visit(String id, Map<String, ModCandidate> selected, Map<String, Set<String>> after,
            Set<String> done, Set<String> visiting, List<ModCandidate> sorted, Resolution resolution) {
        if (done.contains(id) || !selected.containsKey(id)) {
            return;
        }

        if (!visiting.add(id)) {
            resolution.problem(id, "is part of a load-order cycle; order between the mods involved is arbitrary",
                    false);
            return;
        }

        for (String predecessor : new ArrayList<>(after.getOrDefault(id, Set.of()))) {
            visit(predecessor, selected, after, done, visiting, sorted, resolution);
        }

        visiting.remove(id);

        if (done.add(id)) {
            sorted.add(selected.get(id));
        }
    }
}
