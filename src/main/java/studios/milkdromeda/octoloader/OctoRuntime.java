package studios.milkdromeda.octoloader;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studios.milkdromeda.octoloader.knowledge.KnowledgeBase;
import studios.milkdromeda.octoloader.report.CompatReport;
import studios.milkdromeda.octoloader.translate.TranslationContext;
import studios.milkdromeda.octoloader.translate.TranslationPipeline;

import java.nio.file.Path;

/**
 * Entry orchestration: scans the mods directory, runs the translation
 * pipeline, and publishes the compatibility report. Runs once, as early as the
 * active injection mechanism allows.
 */
public final class OctoRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("OctoLoader");
    private static CompatReport report;

    private OctoRuntime() {
    }

    public static synchronized CompatReport runOnce() {
        if (report != null) return report;

        FabricLoader loader = FabricLoader.getInstance();
        Path gameDir = loader.getGameDir();
        String mcVersion = loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");

        KnowledgeBase knowledge = KnowledgeBase.load();
        TranslationContext context = TranslationContext.create(gameDir, mcVersion, knowledge);
        report = new TranslationPipeline().run(gameDir.resolve("mods"), context);
        report.logTable(LOGGER);
        report.writeMarkdown(gameDir);
        return report;
    }

    /** The report of the completed run, or {@code null} before it happened. */
    public static CompatReport currentReport() {
        return report;
    }
}
