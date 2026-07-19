package studios.milkdromeda.octoloader.bootstrap;

import studios.milkdromeda.octoloader.OctoRuntime;

import java.io.File;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Optional java agent entry. When Octo Loader's jar is also registered as
 * {@code -javaagent}, this runs before the game (and Fabric Loader) start:
 * foreign mods are translated immediately and the outputs are appended to the
 * {@code fabric.addMods} property, so they load in the same run — no restart.
 *
 * <p>Without the agent, Octo Loader still works through its pre-launch
 * entrypoint; newly translated mods then need one restart to activate.
 *
 * <p>This code runs on the system classloader, before any game classes exist.
 * It must never break the launch: every failure is caught and reported.
 */
public final class OctoAgent {
    public static final String AGENT_PROPERTY = "octoloader.agent";
    private static final String AGENT_LIBS_DIR = "META-INF/agentlibs/";

    private OctoAgent() {
    }

    public static void premain(String args, Instrumentation instrumentation) {
        System.setProperty(AGENT_PROPERTY, "true");
        try {
            run(instrumentation);
        } catch (Throwable t) {
            System.err.println("[OctoLoader/Agent] Early translation failed; the game will still launch. "
                    + "Octo Loader will retry in fallback mode (restart-based).");
            t.printStackTrace();
            System.setProperty(AGENT_PROPERTY, "failed");
        }
    }

    /** Launcher-Agent-Class entry, used when the JVM supports it. */
    public static void agentmain(String args, Instrumentation instrumentation) {
        premain(args, instrumentation);
    }

    private static void run(Instrumentation instrumentation) throws Exception {
        Path gameDir = Path.of(System.getProperty("octoloader.gameDir", ".")).toAbsolutePath().normalize();
        Path modsDir = gameDir.resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            System.out.println("[OctoLoader/Agent] No mods directory at " + modsDir + "; nothing to do");
            return;
        }

        appendBundledLibraries(instrumentation, gameDir);

        String mcVersion = OctoRuntime.persistedMinecraftVersion(gameDir);
        OctoRuntime.runFromAgent(gameDir, mcVersion);

        Path translatedDir = gameDir.resolve(".octo").resolve("translated");
        String existing = System.getProperty("fabric.addMods");
        String value = existing == null || existing.isBlank()
                ? translatedDir.toString()
                : existing + File.pathSeparator + translatedDir;
        System.setProperty("fabric.addMods", value);
        System.out.println("[OctoLoader/Agent] Registered " + translatedDir + " with fabric.addMods");
    }

    /**
     * The agent classpath is just this jar; the parsing libraries Octo bundles
     * for its mod side live in {@value AGENT_LIBS_DIR} and are appended to the
     * system classloader here (appended jars are searched last, so they never
     * shadow the game's own libraries).
     */
    private static void appendBundledLibraries(Instrumentation instrumentation, Path gameDir) throws Exception {
        Path self = Path.of(OctoAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(self)) return; // dev environment: everything is on the classpath already

        Path libsDir = gameDir.resolve(".octo").resolve("libs");
        Files.createDirectories(libsDir);
        try (JarFile jar = new JarFile(self.toFile())) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(AGENT_LIBS_DIR)
                        || !entry.getName().endsWith(".jar")) {
                    continue;
                }
                Path lib = libsDir.resolve(entry.getName().substring(AGENT_LIBS_DIR.length()));
                if (!Files.exists(lib)) {
                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, lib, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                instrumentation.appendToSystemClassLoaderSearch(new JarFile(lib.toFile()));
            }
        }
    }
}
