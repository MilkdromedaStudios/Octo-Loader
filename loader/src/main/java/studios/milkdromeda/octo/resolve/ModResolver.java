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
        Map<String, ModCandidate> selected = deduplicate(candidates, resolution);
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
                    resolution.problem(entry.getKey(), problem.message(), problem.fatal()));

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
                    + " and ignoring " + loser.metadata().version() + " from " + loser.path().getFileName(), false);
        }

        return selected;
    }

    /** The pseudo-mods every ecosystem expects to exist. */
    private Map<String, Version> platformVersions() {
        Map<String, Version> provided = new HashMap<>();
        Version minecraft = Version.parse(context.minecraftVersion());

        provided.put("minecraft", minecraft);
        provided.put("java", Version.parse(String.valueOf(Runtime.version().feature())));
        provided.put("octoloader", Version.parse(OctoRuntime.VERSION));

        // Octo answers for all four loaders, so mods asking for any of them find
        // it. The versions reported are the ones each ecosystem shipped for this
        // game version, because mods range-check them.
        provided.put("fabricloader", Version.parse("0.16.9"));
        provided.put("fabric", minecraft);
        provided.put("fabric-api", minecraft);
        provided.put("quilt_loader", Version.parse("0.26.0"));
        provided.put("quilt_base", minecraft);
        provided.put("forge", forgeVersionFor(minecraft));
        provided.put("neoforge", neoForgeVersionFor(minecraft));

        return provided;
    }

    /** Forge's version is derived from the game version, e.g. 1.20.1 to 47.x. */
    private static Version forgeVersionFor(Version minecraft) {
        int major = switch (minecraft.part(1)) {
            case 20 -> 47;
            case 19 -> 43;
            case 18 -> 40;
            case 17 -> 37;
            case 16 -> 36;
            case 12 -> 14;
            default -> 50;
        };

        return Version.parse(major + ".0.0");
    }

    /** NeoForge's version tracks the game's minor, e.g. 1.21.1 to 21.1.x. */
    private static Version neoForgeVersionFor(Version minecraft) {
        return Version.parse(minecraft.part(1) + "." + minecraft.part(2) + ".0");
    }

    /** One evaluation of a mod's dependencies. */
    private record Verdict(boolean loadable, List<Finding> problems, List<String> notes) {
        record Finding(String message, boolean fatal) {
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
                        false));
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
