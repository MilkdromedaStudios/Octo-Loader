package studios.milkdromeda.octo.compat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import studios.milkdromeda.octo.compat.api.ApiRuleSet;
import studios.milkdromeda.octo.compat.mapping.MappingRegistry;
import studios.milkdromeda.octo.compat.mapping.MappingSet;
import studios.milkdromeda.octo.launch.LaunchContext;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.transform.ApiMigrationTransformer;
import studios.milkdromeda.octo.transform.RemapTransformer;
import studios.milkdromeda.octo.transform.TransformPipeline;
import studios.milkdromeda.octo.transform.Transformer;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Works out what has to happen to a mod for it to run on today's game.
 *
 * <p>This is the part no other loader has. Fabric, Quilt, Forge and NeoForge all
 * take the same position on a mod built for an older Minecraft: refuse it. That
 * is a defensible position — the mod really was compiled against classes that no
 * longer exist — but it is not the only one. What actually stands between a
 * 1.7.10 mod and a modern runtime is a sequence of mechanical changes: names
 * moved, a few methods changed shape, and some API disappeared. Octo applies
 * those in order and lets the mod run.
 *
 * <ol>
 *   <li><b>Rename.</b> Compose the mapping hops from the mod's namespace to the
 *       running game's, and rewrite every reference. {@code func_71410_x} becomes
 *       {@code getInstance}.</li>
 *   <li><b>Migrate.</b> Apply the rules for changes renaming cannot express —
 *       a method that moved class, a field that became a getter.</li>
 *   <li><b>Stand in.</b> For what is simply gone, generate a class of the right
 *       shape so the rest of the mod links, and calls into the dead API return
 *       harmlessly.</li>
 * </ol>
 *
 * <p>Each step is honest about what it did: everything applied is recorded on
 * the mod and printed by {@code octo scan}, so when a mod misbehaves it is clear
 * what was changed underneath it.
 */
public final class TimeCapsule {
    private static final OctoLog LOG = OctoLog.of(TimeCapsule.class);

    private final LaunchContext context;
    private final Era runtimeEra;
    private final Path userRuleDirectory;

    public TimeCapsule(LaunchContext context) {
        this.context = context;
        this.runtimeEra = Era.forMinecraftVersion(context.minecraftVersion());
        this.userRuleDirectory = context.octoDir().resolve("api");
    }

    public Era runtimeEra() {
        return runtimeEra;
    }

    /** Builds the pipeline a mod's classes pass through, empty when nothing is needed. */
    public TransformPipeline pipelineFor(LoadedMod mod) {
        if (!context.compat().translateOldMods()) {
            return TransformPipeline.empty();
        }

        Era modEra = mod.era();

        if (!modEra.isKnown() || !runtimeEra.isKnown()) {
            return TransformPipeline.empty();
        }

        if (modEra == runtimeEra) {
            return TransformPipeline.empty();
        }

        if (!modEra.isOlderThan(runtimeEra)) {
            // A mod from a newer game than the one running is a different problem:
            // it may call API that does not exist yet, and no rename fixes that.
            mod.recordFix("built for a newer Minecraft (" + modEra + "); loaded without translation");
            return TransformPipeline.empty();
        }

        List<Transformer> transformers = new ArrayList<>();
        ApiRuleSet rules = ApiRuleSet.forEras(modEra, runtimeEra, userRuleDirectory);
        MappingSet mappings = MappingRegistry.get().routeFor(modEra).then(rules.classMappings());

        if (!mappings.isEmpty()) {
            LOG.info("{}: translating from {} to {} using {}", mod.id(), modEra, runtimeEra, mappings);
            transformers.add(new RemapTransformer(mappings));
        } else if (MappingRegistry.get().isEmpty()) {
            mod.recordFix("no mapping tables installed; only API rules were applied");
        }

        if (!rules.isEmpty()) {
            LOG.info("{}: applying {} API rule(s) for {} -> {}", mod.id(), rules.size(), modEra, runtimeEra);
            transformers.add(new ApiMigrationTransformer(rules, context.compat().stubMissingApi()));
        }

        if (transformers.isEmpty()) {
            mod.recordFix("built for " + modEra + " but no translation data was available");
            return TransformPipeline.empty();
        }

        return TransformPipeline.of(transformers);
    }

    /** Human-readable summary of what would be done, for {@code octo scan}. */
    public String describe(LoadedMod mod) {
        Era modEra = mod.era();

        if (!modEra.isKnown()) {
            return "era unknown - will load untranslated";
        }

        if (modEra == runtimeEra) {
            return "matches this Minecraft - no translation needed";
        }

        if (!modEra.isOlderThan(runtimeEra)) {
            return "built for a newer Minecraft (" + modEra + ")";
        }

        ApiRuleSet rules = ApiRuleSet.forEras(modEra, runtimeEra, userRuleDirectory);
        MappingSet mappings = MappingRegistry.get().routeFor(modEra);
        List<String> steps = new ArrayList<>();

        if (!mappings.isEmpty()) {
            steps.add("remap " + mappings.size() + " names");
        }

        if (!rules.isEmpty()) {
            steps.add("apply " + rules.size() + " API rules");
        }

        if (context.compat().stubMissingApi()) {
            steps.add("stand in for anything still missing");
        }

        return steps.isEmpty()
                ? modEra + " mod, but no translation data is installed"
                : modEra + " -> " + runtimeEra + ": " + String.join(", ", steps);
    }
}
