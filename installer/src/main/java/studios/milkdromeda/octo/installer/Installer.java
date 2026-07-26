package studios.milkdromeda.octo.installer;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The installer, {@code installer.jar}.
 *
 * <p>Double-clicked, it opens a window. Run with arguments, or on a machine with
 * no display, it does the same work from the command line — which is what a
 * server operator needs.
 */
public final class Installer {
    public static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        List<String> arguments = new ArrayList<>(List.of(args));

        if (arguments.isEmpty() && !GraphicsEnvironment.isHeadless()) {
            InstallerUi.show();
            return;
        }

        System.exit(runCli(arguments));
    }

    static int runCli(List<String> arguments) {
        ClientInstaller.Log log = message -> System.out.println("  " + message);
        String command = arguments.isEmpty() ? "help" : arguments.remove(0);

        try {
            switch (command) {
                case "client" -> {
                    Path gameDirectory = pathArgument(arguments, "--dir", MinecraftPaths.defaultGameDirectory());
                    String version = argument(arguments, "--mcVersion", null);

                    if (version == null) {
                        List<String> installed = ClientInstaller.installableVersions(gameDirectory);

                        if (installed.isEmpty()) {
                            System.err.println("No Minecraft versions found in " + gameDirectory
                                    + ". Run the version you want once in the launcher first.");
                            return 1;
                        }

                        version = installed.get(0);
                        System.out.println("No --mcVersion given; using the newest installed: " + version);
                    }

                    System.out.println("Installing Octo Loader " + VERSION + " for Minecraft " + version);
                    String profile = new ClientInstaller(gameDirectory, VERSION, log).install(version);
                    System.out.println("Done. Select \"" + profile + "\" in your launcher and put mods in "
                            + gameDirectory.resolve("mods") + ".");
                }
                case "server" -> {
                    Path directory = pathArgument(arguments, "--dir", Path.of("").toAbsolutePath());
                    String serverJar = argument(arguments, "--serverJar", "server.jar");
                    System.out.println("Installing Octo Loader " + VERSION + " into " + directory);
                    new ServerInstaller(directory, VERSION, log).install(serverJar);
                    System.out.println("Done. Start the server with ./start-octo-server.sh");
                }
                case "uninstall" -> {
                    Path gameDirectory = pathArgument(arguments, "--dir", MinecraftPaths.defaultGameDirectory());
                    String version = argument(arguments, "--mcVersion", null);

                    if (version == null) {
                        System.err.println("uninstall needs --mcVersion");
                        return 2;
                    }

                    new ClientInstaller(gameDirectory, VERSION, log).uninstall(version);
                    System.out.println("Removed Octo Loader for Minecraft " + version + ".");
                }
                case "versions" -> {
                    Path gameDirectory = pathArgument(arguments, "--dir", MinecraftPaths.defaultGameDirectory());
                    List<String> installed = ClientInstaller.installableVersions(gameDirectory);
                    System.out.println("Minecraft versions installed in " + gameDirectory + ":");
                    installed.forEach(id -> System.out.println("  " + id));

                    if (installed.isEmpty()) {
                        System.out.println("  (none)");
                    }
                }
                case "help", "--help", "-h" -> usage();
                default -> {
                    System.err.println("unknown command: " + command);
                    usage();
                    return 2;
                }
            }

            return 0;
        } catch (IOException | RuntimeException e) {
            System.err.println("Installation failed: " + e.getMessage());
            return 1;
        }
    }

    private static void usage() {
        System.out.println("""
                Octo Installer %s — installs one loader that runs Fabric, Quilt, Forge,
                NeoForge and older mods together.

                  java -jar installer.jar
                      Open the installer window.

                  java -jar installer.jar client [--dir <.minecraft>] [--mcVersion <version>]
                      Install into a Minecraft client. Defaults to the newest installed version.

                  java -jar installer.jar server [--dir <directory>] [--serverJar server.jar]
                      Install into a server directory and write start scripts.

                  java -jar installer.jar uninstall --mcVersion <version> [--dir <.minecraft>]
                  java -jar installer.jar versions [--dir <.minecraft>]

                No network access is needed: the loader is inside this jar.
                """.formatted(VERSION));
    }

    private static String argument(List<String> arguments, String name, String fallback) {
        int index = arguments.indexOf(name);
        return index >= 0 && index + 1 < arguments.size() ? arguments.get(index + 1) : fallback;
    }

    private static Path pathArgument(List<String> arguments, String name, Path fallback) {
        String value = argument(arguments, name, null);
        return value == null ? fallback : Path.of(value).toAbsolutePath().normalize();
    }

    private Installer() {
    }
}
