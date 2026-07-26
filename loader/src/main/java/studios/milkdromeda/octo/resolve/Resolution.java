package studios.milkdromeda.octo.resolve;

import java.util.ArrayList;
import java.util.List;

import studios.milkdromeda.octo.mod.ModCandidate;

/** The outcome of resolution: what will load, in what order, and what was wrong. */
public final class Resolution {
    private final List<ModCandidate> order = new ArrayList<>();
    private final List<Problem> problems = new ArrayList<>();

    /** @param fatal whether this stopped the mod from loading */
    public record Problem(String modId, String message, boolean fatal) {
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
        problems.add(new Problem(modId, message, fatal));
    }
}
