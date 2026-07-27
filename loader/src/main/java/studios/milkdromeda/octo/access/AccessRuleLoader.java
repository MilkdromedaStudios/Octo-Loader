package studios.milkdromeda.octo.access;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.jar.Manifest;

import studios.milkdromeda.octo.mod.ModSource;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/** Collects the access rules of every loaded mod into one table. */
public final class AccessRuleLoader {
    private static final OctoLog LOG = OctoLog.of(AccessRuleLoader.class);

    /** Where Forge looked for a transformer file before {@code mods.toml} existed. */
    private static final String FMLAT_ATTRIBUTE = "FMLAT";

    private AccessRuleLoader() {
    }

    public static AccessRules collect(List<LoadedMod> mods) {
        AccessRules.Builder merged = AccessRules.builder();
        int contributors = 0;

        for (LoadedMod mod : mods) {
            AccessRules rules = readFrom(mod);

            if (rules.isEmpty()) {
                continue;
            }

            merged.merge(rules);
            contributors++;
            mod.recordFix("applied " + rules);
        }

        AccessRules rules = merged.build();

        if (!rules.isEmpty()) {
            LOG.info("{} from {} mod(s)", rules, contributors);
        }

        return rules;
    }

    private static AccessRules readFrom(LoadedMod mod) {
        AccessRules.Builder rules = AccessRules.builder();

        try (ModSource source = ModSource.open(mod.effectivePath())) {
            for (String path : mod.metadata().accessWideners()) {
                read(source, path, mod, true, rules);
            }

            for (String path : mod.metadata().accessTransformers()) {
                read(source, path, mod, false, rules);
            }

            for (String path : legacyTransformers(source)) {
                read(source, path, mod, false, rules);
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("{}: could not read its access rules: {}", mod.id(), e.toString());
        }

        return rules.build();
    }

    private static void read(ModSource source, String path, LoadedMod mod, boolean widener,
            AccessRules.Builder into) {
        byte[] bytes = source.read(path).orElse(null);

        if (bytes == null) {
            // Declared but absent is common in mods that ship one jar for several
            // versions, and is not worth more than a debug line.
            LOG.debug("{}: declares {} but the jar does not contain it", mod.id(), path);
            return;
        }

        String origin = mod.id() + "!" + path;

        try {
            into.merge(widener
                    ? AccessWidenerReader.read(bytes, origin)
                    : AccessTransformerReader.read(bytes, origin));
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("{}: could not read {}: {}", mod.id(), path, Failures.describe(e));
        }
    }

    /** {@code FMLAT: mymod_at.cfg} in the manifest, the 1.12-and-earlier convention. */
    private static List<String> legacyTransformers(ModSource source) {
        byte[] bytes = source.read("META-INF/MANIFEST.MF").orElse(null);

        if (bytes == null) {
            return List.of();
        }

        try {
            Manifest manifest = new Manifest(new ByteArrayInputStream(bytes));
            String declared = manifest.getMainAttributes().getValue(FMLAT_ATTRIBUTE);

            if (declared == null || declared.isBlank()) {
                return List.of();
            }

            List<String> out = new java.util.ArrayList<>();

            for (String name : declared.split("[\\s,]+")) {
                if (!name.isBlank()) {
                    out.add("META-INF/" + name.trim());
                }
            }

            return out;
        } catch (IOException e) {
            return List.of();
        }
    }
}
