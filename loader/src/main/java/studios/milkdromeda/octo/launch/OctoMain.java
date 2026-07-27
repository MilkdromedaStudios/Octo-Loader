package studios.milkdromeda.octo.launch;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
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
        return gameDirectory(arguments, classPath, System.getenv("APPDATA"), System.getProperty("user.home"));
    }

    static Path gameDirectory(List<String> arguments, String classPath, String appData, String userHome) {
        String declared = argument(arguments, "--gameDir", null);

        if (declared != null && !declared.isBlank() && !declared.contains("${")) {
            return Path.of(declared).toAbsolutePath().normalize();
        }

        Path fromClassPath = installationRoot(classPath);

        if (fromClassPath != null) {
            LOG.warn("--gameDir was not supplied; inferred the Minecraft directory as {}", fromClassPath);
            return fromClassPath;
        }

        // The official Windows launcher uses %APPDATA%\.minecraft.  Prefer it
        // over the process directory when a launcher supplies neither a usable
        // --gameDir nor a class path laid out like a normal installation.
        // Launchers are often started from Program Files, which previously made
        // Octo silently scan Program Files/mods instead of the player's folder.
        if (appData != null && !appData.isBlank()) {
            Path minecraftDirectory = Path.of(appData).resolve(".minecraft").toAbsolutePath().normalize();
            LOG.warn("--gameDir was not supplied; using the standard Minecraft directory {}",
                    minecraftDirectory);
            return minecraftDirectory;
        }

        if (userHome != null && !userHome.isBlank()) {
            Path minecraftDirectory = Path.of(userHome).resolve(".minecraft").toAbsolutePath().normalize();
            LOG.warn("--gameDir was not supplied; using the standard Minecraft directory {}",
                    minecraftDirectory);
            return minecraftDirectory;
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
            Path requested = Path.of(modsDir);
            builder.modDir((requested.isAbsolute() ? requested : gameDir.resolve(requested))
                    .toAbsolutePath().normalize());
        } else {
            defaultModDirectories(gameDir, System.getProperty("java.class.path", ""))
                    .forEach(builder::modDir);
        }

        gameJars.forEach(builder::gameJar);

        LOG.info("Octo Loader starting from {}", gameDir);
        new OctoLauncher(builder.build()).launch();
    }

    /**
     * Chooses the normal instance mods directory, with a conservative fallback
     * for launcher profiles whose {@code --gameDir} points at an empty profile.
     *
     * <p>The official launcher and several third-party launchers can keep the
     * game jar under one Minecraft installation while passing a separate
     * profile directory as {@code --gameDir}. Players commonly leave their jars
     * in the installation's {@code mods} folder. Previously Octo scanned only
     * the empty profile folder and silently loaded nothing. We use the shared
     * folder only when the instance folder contains no mod archives, so a
     * deliberately isolated instance never has two mod sets combined.
     */
    static List<Path> defaultModDirectories(Path gameDir, String classPath) {
        Path instanceMods = gameDir.resolve("mods").toAbsolutePath().normalize();

        if (containsModArchives(instanceMods)) {
            return List.of(instanceMods);
        }

        Path installation = installationRoot(classPath);

        if (installation == null) {
            return List.of(instanceMods);
        }

        Path sharedMods = installation.resolve("mods").toAbsolutePath().normalize();

        if (sharedMods.equals(instanceMods) || !containsModArchives(sharedMods)) {
            return List.of(instanceMods);
        }

        LOG.warn("no mod archives were found in {}; using launcher installation mods folder {}",
                instanceMods, sharedMods);
        return List.of(sharedMods);
    }

    private static boolean containsModArchives(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }

        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> {
                if (!Files.isRegularFile(path)) {
                    return false;
                }

                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return !name.endsWith(".disabled") && (name.endsWith(".jar")
                        || name.endsWith(".zip") || name.endsWith(".litemod"));
            });
        } catch (java.io.IOException error) {
            LOG.warn("could not inspect mods folder {}: {}", directory, error.toString());
            return false;
        }
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
