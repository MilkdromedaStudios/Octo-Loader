package studios.milkdromeda.octo.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import studios.milkdromeda.octo.compat.Era;
import studios.milkdromeda.octo.compat.TimeCapsule;
import studios.milkdromeda.octo.compat.mapping.MappingRegistry;
import studios.milkdromeda.octo.discovery.Discovery;
import studios.milkdromeda.octo.discovery.ModDiscoverer;
import studios.milkdromeda.octo.launch.LaunchContext;
import studios.milkdromeda.octo.launch.GameProvider;
import studios.milkdromeda.octo.launch.LaunchResult;
import studios.milkdromeda.octo.launch.OctoLauncher;
import studios.milkdromeda.octo.launch.OctoMain;
import studios.milkdromeda.octo.mod.ModCandidate;
import studios.milkdromeda.octo.mod.Side;
import studios.milkdromeda.octo.resolve.ModResolver;
import studios.milkdromeda.octo.resolve.Resolution;
import studios.milkdromeda.octo.runtime.LoadedMod;
import studios.milkdromeda.octo.runtime.OctoRuntime;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * The loader's command line.
 *
 * <p>{@code launch} is what a launcher profile invokes. {@code scan} is for
 * people: it reports what Octo found in a mods folder, what era each mod is
 * from, and exactly what it would do to make each one run — without starting
 * the game.
 */
public final class OctoCli {
    public static void main(String[] args) throws Exception {
        List<String> arguments = new ArrayList<>(List.of(args));
        String command = arguments.isEmpty() ? "help" : arguments.remove(0);

        switch (command) {
            case "launch" -> launch(arguments);
            case "scan" -> scan(arguments);
            case "version" -> System.out.println("Octo Loader " + OctoRuntime.VERSION);
            case "help", "--help", "-h" -> usage();
            default -> {
                System.err.println("unknown command: " + command);
                usage();
                System.exit(2);
            }
        }
    }

    private static void usage() {
        System.out.println("""
                Octo Loader — one loader for Fabric, Quilt, Forge, NeoForge and everything before them.

                  octo launch [options] [-- game arguments]
                      Start Minecraft with every mod in the mods folder.

                  octo scan [options]
                      Report what is in the mods folder and what would be done to load it.

                  octo version

                Options:
                  --gameDir <path>      game directory (default: current directory)
                  --modsDir <path>      mods folder, repeatable (default: <gameDir>/mods)
                  --gameJar <path>      Minecraft jar, repeatable
                  --mcVersion <ver>     Minecraft version, when it cannot be read from the jar
                  --side client|server  which side to load for (default: client)
                  --mainClass <name>    game entrypoint (default: read from the jar)
                  --no-translate        do not translate mods built for older versions
                  --no-stubs            do not stand in for classes that no longer exist
                  --strict              stop on the first mod that cannot be loaded
                  --debug               verbose logging
                """);
    }

    private static void launch(List<String> arguments) throws Exception {
        Options options = Options.parse(arguments);
        LaunchContext context = options.toContext();
        new OctoLauncher(context).launch();
    }

    private static void scan(List<String> arguments) {
        Options options = Options.parse(arguments);
        LaunchContext context = options.toContext();

        MappingRegistry.install(MappingRegistry.load(context.octoDir().resolve("mappings"),
                MappingRegistry.namespaceOf(Era.forMinecraftVersion(context.minecraftVersion()))));

        Discovery discovery = new ModDiscoverer(context.octoDir().resolve("nested")).discover(context.modDirs());
        List<ModCandidate> candidates = discovery.candidates();
        Resolution resolution = new ModResolver(context).resolve(candidates);
        TimeCapsule timeCapsule = new TimeCapsule(context);

        System.out.println();
        System.out.println("Octo Loader " + OctoRuntime.VERSION + " - scan of " + context.modDirs());
        System.out.println("Minecraft " + (context.minecraftVersion().isEmpty() ? "(unknown)" : context.minecraftVersion())
                + ", era " + timeCapsule.runtimeEra() + ", side " + context.side());
        System.out.println();

        Map<String, Integer> byFormat = new LinkedHashMap<>();

        for (ModCandidate candidate : candidates) {
            byFormat.merge(candidate.format().displayName(), 1, Integer::sum);
        }

        System.out.printf("%-28s %-14s %-12s %-12s %s%n", "MOD", "VERSION", "FORMAT", "ERA", "PLAN");
        System.out.println("-".repeat(110));

        for (ModCandidate candidate : candidates) {
            LoadedMod view = new LoadedMod(candidate);
            String plan = timeCapsule.describe(view);

            System.out.printf("%-28s %-14s %-12s %-12s %s%n",
                    truncate(candidate.id(), 28),
                    truncate(candidate.metadata().version().raw(), 14),
                    truncate(candidate.format().displayName(), 12),
                    truncate(candidate.era().id(), 12),
                    plan);

            for (String note : candidate.notes()) {
                System.out.println("    note: " + note);
            }
        }

        System.out.println();
        System.out.println("Formats found: " + (byFormat.isEmpty() ? "none" : byFormat));

        // The whole point of scanning is to find out why something is missing,
        // so the files that produced no mod are the interesting half of this.
        if (!discovery.rejections().isEmpty()) {
            System.out.println();
            System.out.println("Not loaded:");
            discovery.rejections().forEach(rejection -> System.out.println("  " + rejection));
        }

        if (!resolution.problems().isEmpty()) {
            System.out.println();
            System.out.println("Resolution:");
            resolution.problems().forEach(problem -> System.out.println("  " + problem));
        }

        System.out.println();
        System.out.println(resolution.order().size() + " of " + candidates.size() + " mod(s) would load.");
    }

    private static String truncate(String value, int width) {
        return value.length() <= width ? value : value.substring(0, width - 1) + "...";
    }

    /** Command-line options shared by every subcommand. */
    private record Options(Path gameDir, List<Path> modDirs, List<Path> gameJars, String minecraftVersion,
                           Side side, String mainClass, List<String> gameArguments,
                           boolean translate, boolean stubs, boolean strict) {
        static Options parse(List<String> arguments) {
            Path gameDir = Path.of("").toAbsolutePath();
            List<Path> modDirs = new ArrayList<>();
            List<Path> gameJars = new ArrayList<>();
            List<String> gameArguments = new ArrayList<>();
            String minecraftVersion = "";
            String mainClass = null;
            Side side = Side.CLIENT;
            boolean translate = true;
            boolean stubs = true;
            boolean strict = false;

            for (int i = 0; i < arguments.size(); i++) {
                String argument = arguments.get(i);

                switch (argument) {
                    case "--gameDir" -> gameDir = Path.of(arguments.get(++i)).toAbsolutePath();
                    case "--modsDir" -> modDirs.add(Path.of(arguments.get(++i)).toAbsolutePath());
                    case "--gameJar" -> gameJars.add(Path.of(arguments.get(++i)).toAbsolutePath());
                    case "--mcVersion" -> minecraftVersion = arguments.get(++i);
                    case "--mainClass" -> mainClass = arguments.get(++i);
                    case "--side" -> side = Side.parse(arguments.get(++i), Side.CLIENT);
                    case "--no-translate" -> translate = false;
                    case "--no-stubs" -> stubs = false;
                    case "--strict" -> strict = true;
                    case "--lenient" -> strict = false;
                    case "--debug" -> OctoLog.setThreshold(OctoLog.Level.DEBUG);
                    case "--" -> {
                        gameArguments.addAll(arguments.subList(i + 1, arguments.size()));
                        i = arguments.size();
                    }
                    default -> gameArguments.add(argument);
                }
            }

            return new Options(gameDir, modDirs, gameJars, minecraftVersion, side, mainClass, gameArguments,
                    translate, stubs, strict);
        }

        LaunchContext toContext() {
            GameProvider provider = new GameProvider(gameJars);
            String version = minecraftVersion.isBlank() ? provider.detectVersion().orElse("") : minecraftVersion;
            String main = mainClass != null ? mainClass : provider.detectMainClass(side).orElse(null);

            LaunchContext.Builder builder = LaunchContext.builder(gameDir)
                    .side(side)
                    .minecraftVersion(version)
                    .mainClass(main)
                    .launchArguments(gameArguments)
                    .compat(new LaunchContext.CompatOptions()
                            .translateOldMods(translate)
                            .stubMissingApi(stubs)
                            .strict(strict));

            modDirs.forEach(builder::modDir);
            gameJars.forEach(builder::gameJar);

            // Same reason as OctoMain: a library a mod mixes into has to be
            // inside the loader with the game, not outside it with the loader.
            OctoMain.libraryJarsOnClassPath(gameJars).forEach(builder::libraryJar);
            return builder.build();
        }
    }

    private OctoCli() {
    }
}
