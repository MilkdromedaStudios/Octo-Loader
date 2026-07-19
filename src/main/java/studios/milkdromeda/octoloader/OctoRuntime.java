package studios.milkdromeda.octoloader;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studios.milkdromeda.octoloader.bootstrap.ModInjector;
import studios.milkdromeda.octoloader.bootstrap.OctoAgent;
import studios.milkdromeda.octoloader.knowledge.KnowledgeBase;
import studios.milkdromeda.octoloader.report.CompatReport;
import studios.milkdromeda.octoloader.report.CompatStatus;
import studios.milkdromeda.octoloader.translate.TranslationContext;
import studios.milkdromeda.octoloader.translate.TranslationPipeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Entry orchestration for both execution contexts:
 * <ul>
 *   <li>{@link #runFromAgent} — inside {@code premain}, before the game exists;
 *       translates and stages jars for same-run injection.</li>
 *   <li>{@link #runFromPreLaunch} — Fabric's earliest mod entrypoint; (re)runs
 *       the pipeline for reporting, and performs the actual translation when no
 *       agent is installed (restart-based activation).</li>
 * </ul>
 */
public final class OctoRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("OctoLoader");
    private static CompatReport report;

    private OctoRuntime() {
    }

    public static synchronized CompatReport runFromPreLaunch() {
        if (report != null) return report;

        FabricLoader loader = FabricLoader.getInstance();
        Path gameDir = loader.getGameDir();
        String mcVersion = loader.getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
        persistRuntimeInfo(gameDir, mcVersion);

        boolean agentActive = "true".equals(System.getProperty(OctoAgent.AGENT_PROPERTY));
        ModInjector injector = agentActive
                ? ModInjector.agent(gameDir.resolve(".octo"))
                : ModInjector.modsDir(gameDir.resolve("mods"));
        LOGGER.info("Octo Loader running (Minecraft {}, injection: {})", mcVersion, injector.name());

        TranslationContext context = TranslationContext.create(gameDir, mcVersion, KnowledgeBase.load(),
                injector, id -> FabricLoader.getInstance().isModLoaded(id));
        report = new TranslationPipeline().run(gameDir.resolve("mods"), context);
        report.logTable(LOGGER);
        report.writeMarkdown(gameDir);
        return report;
    }

    /** Agent context: no Fabric Loader yet, so no loaded-mod checks and no report file. */
    public static void runFromAgent(Path gameDir, String mcVersion) {
        TranslationContext context = TranslationContext.create(gameDir, mcVersion, KnowledgeBase.load(),
                ModInjector.agent(gameDir.resolve(".octo")), id -> false);
        CompatReport agentReport = new TranslationPipeline().run(gameDir.resolve("mods"), context);
        long staged = agentReport.entries().stream()
                .filter(e -> e.status() == CompatStatus.TRANSLATED)
                .count();
        LOGGER.info("Octo agent staged {} translated mod(s) for this run", staged);
    }

    /** The report of the completed pre-launch run, or {@code null} before it happened. */
    public static CompatReport currentReport() {
        return report;
    }

    /**
     * The Minecraft version recorded by the previous pre-launch run. The agent
     * runs before the game can be asked directly, so it uses this; the first
     * agent run on a fresh installation sees {@code "unknown"} and defers
     * version gating to the knowledge base's era matching.
     */
    public static String persistedMinecraftVersion(Path gameDir) {
        Path file = runtimeInfoFile(gameDir);
        if (!Files.isRegularFile(file)) return "unknown";
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            props.load(in);
        } catch (IOException e) {
            return "unknown";
        }
        return props.getProperty("mcVersion", "unknown");
    }

    private static void persistRuntimeInfo(Path gameDir, String mcVersion) {
        Properties props = new Properties();
        props.setProperty("mcVersion", mcVersion);
        try {
            Files.createDirectories(runtimeInfoFile(gameDir).getParent());
            try (OutputStream out = Files.newOutputStream(runtimeInfoFile(gameDir))) {
                props.store(out, "Octo Loader runtime info, read by the java agent on the next launch");
            }
        } catch (IOException e) {
            LOGGER.warn("Could not persist runtime info", e);
        }
    }

    private static Path runtimeInfoFile(Path gameDir) {
        return gameDir.resolve(".octo").resolve("runtime.properties");
    }
}
