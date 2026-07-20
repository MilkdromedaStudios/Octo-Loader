package studios.milkdromeda.octoloader.replace;

import studios.milkdromeda.octoloader.detect.DetectedMod;
import studios.milkdromeda.octoloader.knowledge.ApiStep;
import studios.milkdromeda.octoloader.knowledge.KnowledgeBase;
import studios.milkdromeda.octoloader.knowledge.McVersionIndex;
import studios.milkdromeda.octoloader.knowledge.McVersions;
import studios.milkdromeda.octoloader.translate.TranslationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether a mod built for an older Minecraft can have its API
 * references replaced to run on the current one.
 *
 * <p>The declared target range is resolved against the documented version
 * timeline; the newest version the range was actually built against (exclusive
 * upper bounds don't count) is the mod's API level. From there the per-version
 * documentation is walked step by step up to the running version: if every
 * step is mechanically documented the composed chain becomes a
 * {@link ReplacementPlan.Replace}; the first step that is descriptive-only or
 * undocumented turns the plan into an honest {@link ReplacementPlan.Blocked}
 * naming exactly where and why the chain ends.
 */
public final class ApiReplacement {
    private ApiReplacement() {
    }

    public static ReplacementPlan plan(DetectedMod mod, TranslationContext context) {
        return plan(mod.targetMinecraft(), context.runningMinecraft(), context.knowledge());
    }

    public static ReplacementPlan plan(String declaredRange, String running, KnowledgeBase knowledge) {
        McVersionIndex index = knowledge.versionIndex();
        if (running == null || "unknown".equals(running)) {
            return new ReplacementPlan.None("running Minecraft version unknown");
        }
        Optional<String> runningId = index.resolve(running);
        if (runningId.isEmpty()) {
            return new ReplacementPlan.None("running Minecraft " + running + " is not in the documented timeline");
        }

        String compiledAgainst = resolveCompiledAgainst(declaredRange, index);
        if (compiledAgainst == null) {
            return new ReplacementPlan.None("declared target does not resolve to a documented version");
        }
        int order = index.compare(compiledAgainst, runningId.get());
        if (order == 0) {
            return new ReplacementPlan.None("targets the running Minecraft version");
        }
        if (order > 0) {
            return new ReplacementPlan.Blocked(compiledAgainst, runningId.get(),
                    "built for Minecraft " + compiledAgainst + ", newer than the running " + runningId.get()
                            + "; API replacement only works backwards");
        }

        List<ApiStep> steps = new ArrayList<>();
        for (String stepStart : index.stepStartsBetween(compiledAgainst, runningId.get())) {
            Optional<ApiStep> step = knowledge.apiDocs().stepFrom(stepStart);
            if (step.isEmpty()) {
                return new ReplacementPlan.Blocked(compiledAgainst, runningId.get(),
                        "built for Minecraft " + compiledAgainst + "; no API documentation for the step "
                                + stepStart + " → " + index.successor(stepStart).orElse("?"));
            }
            if (!step.get().full()) {
                return new ReplacementPlan.Blocked(compiledAgainst, runningId.get(),
                        "built for Minecraft " + compiledAgainst + "; the documented API chain to "
                                + runningId.get() + " breaks at " + step.get().from() + " → " + step.get().to()
                                + ": " + step.get().summary());
            }
            steps.add(step.get());
        }
        return new ReplacementPlan.Replace(compiledAgainst, runningId.get(), steps);
    }

    /**
     * The newest documented version the declared range names as an actual
     * target — the API level the mod was compiled against. Exclusive upper
     * bounds ({@code "<X"}, {@code "[a,X)"}) name versions the range excludes,
     * so they are skipped.
     */
    private static String resolveCompiledAgainst(String declaredRange, McVersionIndex index) {
        if (declaredRange == null) return null;
        // Whole-string resolution first: beta ids like "b1.7.3" carry a prefix
        // the numeric tokenizer would strip.
        Optional<String> exact = index.resolve(declaredRange);
        if (exact.isPresent()) return exact.get();

        String newest = null;
        for (McVersions.RangeToken token : McVersions.rangeTokens(declaredRange)) {
            if (token.exclusiveUpperBound()) continue;
            Optional<String> resolved = index.resolve(token.value());
            if (resolved.isEmpty()) continue;
            if (newest == null || index.compare(resolved.get(), newest) > 0) {
                newest = resolved.get();
            }
        }
        return newest;
    }
}
