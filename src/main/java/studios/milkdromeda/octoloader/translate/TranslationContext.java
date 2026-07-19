package studios.milkdromeda.octoloader.translate;

import studios.milkdromeda.octoloader.bootstrap.ModInjector;
import studios.milkdromeda.octoloader.knowledge.KnowledgeBase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * Shared state handed to translators.
 *
 * @param octoDir          Octo Loader's working directory ({@code <gameDir>/.octo})
 * @param runningMinecraft the Minecraft version this game is running, or
 *                         {@code "unknown"} in agent context before first launch
 * @param knowledge        the loader knowledge base
 * @param injector         placement + activation mechanism for generated jars
 * @param loadedModCheck   whether a mod id is live in the current run (always
 *                         false in agent context, where the loader hasn't started)
 */
public record TranslationContext(
        Path octoDir,
        String runningMinecraft,
        KnowledgeBase knowledge,
        ModInjector injector,
        Predicate<String> loadedModCheck
) {
    public static TranslationContext create(Path gameDir, String runningMinecraft, KnowledgeBase knowledge,
                                            ModInjector injector, Predicate<String> loadedModCheck) {
        TranslationContext context = new TranslationContext(gameDir.resolve(".octo"), runningMinecraft,
                knowledge, injector, loadedModCheck);
        try {
            Files.createDirectories(injector.outputDir());
            Files.createDirectories(context.octoDir());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create Octo Loader directories", e);
        }
        return context;
    }
}
