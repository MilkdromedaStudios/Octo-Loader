package studios.milkdromeda.octo.resolve;

import java.util.ArrayList;
import java.util.List;

import studios.milkdromeda.octo.mod.ModCandidate;

/** The outcome of resolution: what will load, in what order, and what was wrong. */
public final class Resolution {
    private final List<ModCandidate> order = new ArrayList<>();
    private final List<Problem> problems = new ArrayList<>();

    /**
     * What kind of problem this is, so the launch log can say "34 mods declare a
     * different loader version" once instead of thirty-four times.
     */
    public enum Kind {
        /** Anything without a better home; always reported individually. */
        GENERAL,
        /** The same mod id arrived in more than one jar. */
        DUPLICATE,
        /** A bound on Minecraft or on a loader that Octo deliberately does not enforce. */
        PLATFORM_VERSION
    }

    /** @param fatal whether this stopped the mod from loading */
    public record Problem(String modId, String message, boolean fatal, Kind kind) {
        public Problem(String modId, String message, boolean fatal) {
            this(modId, message, fatal, Kind.GENERAL);
        }

        @Override
        public String toString() {
            return (fatal ? "[skipped] " : "[warning] ") + modId + ": " + message;
        }
    }

    public List<ModCandidate> order() {
        return List.copyOf(order);
    }

    public List<Problem> problems() {
        return List.copyOf(problems);
    }

    public List<Problem> fatalProblems() {
        return problems.stream().filter(Problem::fatal).toList();
    }

    public boolean isClean() {
        return problems.isEmpty();
    }

    void add(ModCandidate candidate) {
        order.add(candidate);
    }

    void problem(String modId, String message, boolean fatal) {
        problem(modId, message, fatal, Kind.GENERAL);
    }

    void problem(String modId, String message, boolean fatal, Kind kind) {
        problems.add(new Problem(modId, message, fatal, kind));
    }

    public List<Problem> problems(Kind kind) {
        return problems.stream().filter(problem -> problem.kind() == kind && !problem.fatal()).toList();
    }
}
