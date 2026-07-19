package studios.milkdromeda.octoloader.translate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studios.milkdromeda.octoloader.detect.DetectedMod;
import studios.milkdromeda.octoloader.detect.ModEcosystem;
import studios.milkdromeda.octoloader.discovery.ModScanner;
import studios.milkdromeda.octoloader.knowledge.Era;
import studios.milkdromeda.octoloader.knowledge.McVersions;
import studios.milkdromeda.octoloader.report.CompatReport;
import studios.milkdromeda.octoloader.report.CompatStatus;
import studios.milkdromeda.octoloader.report.ModReportEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Orchestrates the whole flow: scan the mods directory, identify every jar,
 * route translatable ones to their ecosystem translator, and record a verdict
 * for each in the compatibility report.
 */
public final class TranslationPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("OctoLoader/Pipeline");

    private final List<Translator> translators = new ArrayList<>();

    public TranslationPipeline() {
        for (Translator translator : ServiceLoader.load(Translator.class, getClass().getClassLoader())) {
            translators.add(translator);
        }
    }

    public CompatReport run(Path modsDir, TranslationContext context) {
        CompatReport report = new CompatReport();
        for (DetectedMod mod : new ModScanner().scan(modsDir)) {
            if (TranslationCache.isGeneratedJar(mod.path())) {
                // Octo's own outputs are bookkeeping, not user mods; drop the
                // ones whose source jar disappeared so they don't linger.
                if (TranslationCache.readAttribute(mod.path(), TranslationCache.ATTR_TRANSLATED_FROM).isPresent()
                        && sourceGone(mod.path(), modsDir)) {
                    deleteQuietly(mod.path());
                }
                continue;
            }
            report.add(verdict(mod, context));
        }
        return report;
    }

    private ModReportEntry verdict(DetectedMod mod, TranslationContext context) {
        if (mod.ecosystem() == ModEcosystem.FABRIC) {
            return new ModReportEntry(mod, CompatStatus.NATIVE, "loaded by Fabric Loader");
        }
        if (mod.ecosystem() == ModEcosystem.UNKNOWN) {
            return new ModReportEntry(mod, CompatStatus.UNRECOGNIZED,
                    mod.detail() != null ? mod.detail() : "no recognizable mod metadata");
        }

        Optional<Era> era = context.knowledge().eraFor(mod);
        boolean versionCompatible = mod.targetMinecraft() == null
                || McVersions.couldTarget(mod.targetMinecraft(), context.runningMinecraft());

        if (era.isPresent() && !era.get().translatable()) {
            // Server plugins declare a minimum Bukkit api-version, not a Minecraft
            // target — for them the blocker is always the missing ecosystem.
            boolean plugin = mod.ecosystem() == ModEcosystem.PAPER_PLUGIN
                    || mod.ecosystem() == ModEcosystem.BUKKIT_PLUGIN;
            CompatStatus status = !plugin && !versionCompatible
                    ? CompatStatus.UNSUPPORTED_VERSION
                    : CompatStatus.UNSUPPORTED_ECOSYSTEM;
            return new ModReportEntry(mod, status, era.get().summary());
        }
        if (!versionCompatible) {
            return new ModReportEntry(mod, CompatStatus.UNSUPPORTED_VERSION,
                    "built for Minecraft " + mod.targetMinecraft() + ", running " + context.runningMinecraft());
        }

        for (Translator translator : translators) {
            if (translator.supports(mod)) {
                try {
                    return translator.translate(mod, context);
                } catch (RuntimeException e) {
                    LOGGER.error("Translator {} failed for {}", translator.id(), mod.path(), e);
                    return new ModReportEntry(mod, CompatStatus.ERROR,
                            "translator " + translator.id() + " failed: " + e.getMessage());
                }
            }
        }
        return new ModReportEntry(mod, CompatStatus.UNSUPPORTED_ECOSYSTEM,
                "no translator available yet for " + mod.ecosystem().displayName());
    }

    private static boolean sourceGone(Path generatedJar, Path modsDir) {
        String name = generatedJar.getFileName().toString();
        String sourceBase = name.substring(0, name.length() - TranslationCache.GENERATED_SUFFIX.length());
        return !Files.exists(modsDir.resolve(sourceBase + ".jar"));
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.delete(path);
            LOGGER.info("Removed stale generated jar {}", path.getFileName());
        } catch (IOException e) {
            LOGGER.warn("Could not remove stale generated jar {}", path, e);
        }
    }
}
