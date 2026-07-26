package studios.milkdromeda.octo.launch;

import java.util.List;

import studios.milkdromeda.octo.resolve.Resolution;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;

/** What a launch produced, whether or not the game itself was started. */
public record LaunchResult(OctoRuntime runtime, Resolution resolution, OctoClassLoader classLoader,
                           List<LoadedMod> loaded, List<LoadedMod> failed) {
    public boolean isClean() {
        return failed.isEmpty() && resolution.fatalProblems().isEmpty();
    }

    public LoadedMod mod(String id) {
        for (LoadedMod mod : loaded) {
            if (mod.id().equals(id)) {
                return mod;
            }
        }

        return null;
    }

    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append(loaded.size()).append(" mod(s) loaded");

        if (!failed.isEmpty()) {
            out.append(", ").append(failed.size()).append(" failed");
        }

        List<Resolution.Problem> problems = resolution.problems();

        if (!problems.isEmpty()) {
            out.append(", ").append(problems.size()).append(" note(s)");
        }

        return out.toString();
    }
}
