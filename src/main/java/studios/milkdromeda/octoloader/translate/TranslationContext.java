package studios.milkdromeda.octoloader.translate;

import studios.milkdromeda.octoloader.knowledge.KnowledgeBase;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared state handed to translators.
 *
 * @param octoDir          Octo Loader's working directory ({@code <gameDir>/.octo})
 * @param translatedDir    output directory for generated Fabric jars
 * @param runningMinecraft the Minecraft version this game is running
 * @param knowledge        the loader knowledge base
 * @param outputJars       collector: every jar placed here is handed to the injector
 */
public record TranslationContext(
        Path octoDir,
        Path translatedDir,
        String runningMinecraft,
        KnowledgeBase knowledge,
        List<Path> outputJars
) {
    public static TranslationContext create(Path gameDir, String runningMinecraft, KnowledgeBase knowledge) {
        Path octoDir = gameDir.resolve(".octo");
        return new TranslationContext(octoDir, octoDir.resolve("translated"), runningMinecraft,
                knowledge, new ArrayList<>());
    }
}
