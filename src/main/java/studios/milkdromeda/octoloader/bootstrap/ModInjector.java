package studios.milkdromeda.octoloader.bootstrap;

import java.nio.file.Path;

/**
 * Where translated jars go and how they become live mods.
 *
 * <p>Two mechanisms exist, both free of Fabric Loader internals:
 * <ul>
 *   <li><b>agent</b> — Octo's {@code premain} ran before the game launcher, so
 *       outputs written to {@code .octo/translated} are appended to the
 *       {@code fabric.addMods} property and load <em>this same run</em>.</li>
 *   <li><b>mods-dir</b> — no agent installed: outputs are written into the
 *       {@code mods/} folder as regular Fabric jars, which Fabric Loader picks
 *       up on the <em>next</em> launch (restart required once per new mod).</li>
 * </ul>
 *
 * @param name           mechanism id for logs
 * @param outputDir      directory translators write generated jars into
 * @param activationNote report wording for a freshly generated jar
 * @param sameRun        whether generated jars load in the current run
 */
public record ModInjector(String name, Path outputDir, String activationNote, boolean sameRun) {
    public static ModInjector agent(Path octoDir) {
        return new ModInjector("agent", octoDir.resolve("translated"),
                "translated; loads this run via the Octo agent", true);
    }

    public static ModInjector modsDir(Path modsDir) {
        return new ModInjector("mods-dir", modsDir,
                "translated; restart the game to activate it", false);
    }
}
