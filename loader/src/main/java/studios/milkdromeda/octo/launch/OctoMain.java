package studios.milkdromeda.octo.launch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * The entrypoint a launcher starts.
 *
 * <p>This is the class named in the version profile the installer writes, so it
 * receives exactly the arguments the Minecraft launcher passes to the game —
 * {@code --gameDir}, {@code --version}, {@code --assetsDir} and the rest — and
 * has to work out everything else from the environment. The game's own jar
 * arrives on the class path, put there by the launcher from the inherited
 * vanilla version, so that is where it is looked for.
 */
public final class OctoMain {
    private static final OctoLog LOG = OctoLog.of(OctoMain.class);

    public static void main(String[] args) throws Exception {
        List<String> arguments = List.of(args);
        Path gameDir = gameDirectory(arguments, System.getProperty("java.class.path", ""));
        Path logFile = gameDir.resolve(".octo").resolve("logs").resolve("octo-loader.log");

        try {
            OctoLog.openFile(logFile);
            LOG.info("bootstrap started; persistent log is {}", logFile);
            launch(arguments, gameDir);
        } catch (Throwable error) {
            LOG.error("launch failed before Minecraft could start: {}", error.toString());
            OctoLog.writeThrowable(error);
            if (error instanceof Exception exception) {
                throw exception;
            }
            throw (Error) error;
        }
    }

    /**
     * Locates the installation even when a launcher does not pass {@code --gameDir}.
     *
     * <p>Older Octo profiles did not add that argument, and some third-party
     * launchers omit or fail to expand it. Falling back to the process working
     * directory is unreliable because launchers commonly start Java from their
     * own application directory. The Minecraft version jar and libraries are
     * already on the class path, so either standard {@code versions/} or
     * {@code libraries/} layout gives us the actual installation root.
     */
    static Path gameDirectory(List<String> arguments, String classPath) {
        String declared = argument(arguments, "--gameDir", null);

        if (declared != null && !declared.isBlank() && !declared.contains("${")) {
            return Path.of(declared).toAbsolutePath().normalize();
        }

        Path fromClassPath = installationRoot(classPath);

        if (fromClassPath != null) {
            LOG.warn("--gameDir was not supplied; inferred the Minecraft directory as {}", fromClassPath);
            return fromClassPath;
        }

        Path workingDirectory = Path.of(".").toAbsolutePath().normalize();
        LOG.warn("--gameDir was not supplied and the installation could not be inferred; using {}",
                workingDirectory);
        return workingDirectory;
    }

    private static Path installationRoot(String classPath) {
        Path libraryRoot = null;

        for (String entry : classPath.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }

            Path path;

            try {
                path = Path.of(entry).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
                continue;
            }

            for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) {
                Path name = parent.getFileName();

                if (name == null) {
                    continue;
                }

                if (name.toString().equals("versions")) {
                    return parent.getParent();
                }

                if (libraryRoot == null && name.toString().equals("libraries")) {
                    libraryRoot = parent.getParent();
                }
            }
        }

        return libraryRoot;
    }

    private static void launch(List<String> arguments, Path gameDir) throws Exception {
        Side side = detectSide(arguments);

        List<Path> gameJars = gameJarsOnClassPath();
        GameProvider provider = new GameProvider(gameJars);

        String version = provider.detectVersion()
                .orElseGet(() -> argument(arguments, "--mcVersion", argument(arguments, "--version", "")));

        String mainClass = provider.detectMainClass(side)
                .orElse(side == Side.SERVER ? GameProvider.SERVER_MAIN : GameProvider.CLIENT_MAIN);

        if (System.getProperty("octo.debug") != null) {
            OctoLog.setThreshold(OctoLog.Level.DEBUG);
        }

        LaunchContext.Builder builder = LaunchContext.builder(gameDir)
                .side(side)
                .minecraftVersion(version)
                .mainClass(mainClass)
                .launchArguments(arguments)
                .compat(compatOptions());

        String modsDir = argument(arguments, "--modsDir", null);

        if (modsDir != null) {
            builder.modDir(Path.of(modsDir).toAbsolutePath());
        }

        gameJars.forEach(builder::gameJar);

        LOG.info("Octo Loader starting from {}", gameDir);
        new OctoLauncher(builder.build()).launch();
    }

    /**
     * System properties rather than arguments, because the Minecraft launcher
     * passes its own argument list through unchanged and adding to it upsets
     * the game's parser.
     */
    private static LaunchContext.CompatOptions compatOptions() {
        return new LaunchContext.CompatOptions()
                .translateOldMods(!Boolean.getBoolean("octo.noTranslate"))
                .stubMissingApi(!Boolean.getBoolean("octo.noStubs"))
                .relaxVersionChecks(!Boolean.getBoolean("octo.strictVersions"))
                .failOnUnloadableMod(Boolean.getBoolean("octo.strict"));
    }

    private static Side detectSide(List<String> arguments) {
        String declared = argument(arguments, "--side", null);

        if (declared != null) {
            return Side.parse(declared, Side.CLIENT);
        }

        // A client launch always passes an access token or a username.
        boolean client = arguments.contains("--accessToken") || arguments.contains("--username")
                || arguments.contains("--assetsDir");
        return client ? Side.CLIENT : Side.SERVER;
    }

    private static String argument(List<String> arguments, String name, String fallback) {
        int index = arguments.indexOf(name);
        return index >= 0 && index + 1 < arguments.size() ? arguments.get(index + 1) : fallback;
    }

    /**
     * Picks the game's jars out of the class path.
     *
     * <p>The launcher hands over a class path with the loader, the game and a
     * hundred libraries on it. The game jars are the ones carrying either the
     * modern {@code version.json} or a recognisable entrypoint class.
     */
    static List<Path> gameJarsOnClassPath() {
        List<Path> out = new ArrayList<>();
        String classPath = System.getProperty("java.class.path", "");

        for (String entry : classPath.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }

            Path path = Path.of(entry);

            if (!Files.isRegularFile(path)) {
                continue;
            }

            GameProvider probe = new GameProvider(List.of(path));

            if (probe.detectVersion().isPresent()
                    || probe.contains(GameProvider.CLIENT_MAIN)
                    || probe.contains(GameProvider.SERVER_MAIN)
                    || probe.contains("net.minecraft.server.MinecraftServer")) {
                out.add(path);
            }
        }

        if (out.isEmpty()) {
            LOG.warn("no Minecraft jar found on the class path; mods will load but the game will not start");
        }

        return out;
    }

    private OctoMain() {
    }
}
