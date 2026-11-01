package studios.milkdromeda.octo.launcher;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Manifest;

import studios.milkdromeda.octo.common.WorldBackups;
import studios.milkdromeda.octo.installer.ClientInstaller;
import studios.milkdromeda.octo.installer.Installer;

/**
 * Octo Launcher, {@code octo-launcher.jar}: the app around Minecraft.
 *
 * <p>It keeps Octo installed for the versions the player picks, backs worlds
 * up and puts them back, keeps itself and the loader current, signs the
 * player in with Microsoft, downloads the game and its Java from Mojang and
 * starts it — or opens the official Minecraft Launcher on the Octo
 * installation, for those who prefer it.
 *
 * <p>Double-clicked it opens a window; with arguments it works from the
 * command line ({@code help} lists them).
 */
public final class Launcher {
    private static volatile String build;

    private Launcher() {
    }

    public static void main(String[] args) throws Exception {
        List<String> arguments = new ArrayList<>(List.of(args));

        if (!arguments.isEmpty() && arguments.get(0).equals("--apply-update")) {
            Updates.apply(Path.of(arguments.get(1)), Path.of(arguments.get(2)), Long.parseLong(arguments.get(3)),
                    arguments.size() > 4 ? arguments.get(4) : null);
            return;
        }

        if (arguments.isEmpty() && !GraphicsEnvironment.isHeadless()) {
            studios.milkdromeda.octo.launcher.ui.ClientWindow.show();
            return;
        }

        System.exit(cli(arguments));
    }

    static int cli(List<String> arguments) {
        String command = arguments.isEmpty() ? "help" : arguments.remove(0);
        LauncherSettings settings = LauncherSettings.load();
        ClientInstaller.Log log = message -> System.out.println("  " + message);

        try {
            switch (command) {
                case "play" -> {
                    String version = arguments.isEmpty() ? null : arguments.get(0);

                    if (Play.launchesItself(settings)) {
                        Play.Running running = Play.launch(settings, Play.accounts(), version, terminalProgress());
                        System.out.println("\nStarted Minecraft " + running.version()
                                + (running.withOcto() ? "" : " without Octo (it needs 1.13 or newer)")
                                + "; its output is in " + running.log());
                    } else {
                        System.out.println(Play.prepareAndOpen(settings, version, log));
                    }
                }
                case "download" -> {
                    boolean repair = arguments.remove("--repair");
                    String version = arguments.isEmpty() ? null : arguments.get(0);
                    String id = Play.download(settings, version, repair, terminalProgress());
                    System.out.println("\nReady to play: " + id);
                }
                case "sign-in" -> {
                    String clientId = Play.clientId(settings);

                    if (clientId == null) {
                        throw new IOException("this build has no Microsoft sign-in; set OCTO_MSA_CLIENT_ID");
                    }

                    studios.milkdromeda.octo.launcher.auth.MicrosoftAuth auth =
                            new studios.milkdromeda.octo.launcher.auth.MicrosoftAuth(clientId);
                    studios.milkdromeda.octo.launcher.auth.MicrosoftAuth.DeviceCode code = auth.start();
                    System.out.println("Open " + code.verificationUri() + " and enter the code " + code.userCode());
                    studios.milkdromeda.octo.launcher.auth.Account account = auth.finish(code, () -> false);
                    Play.accounts().put(account);
                    System.out.println("Signed in as " + account.name());
                }
                case "sign-out" -> {
                    studios.milkdromeda.octo.launcher.auth.Accounts accounts = Play.accounts();
                    studios.milkdromeda.octo.launcher.auth.Account account = accounts.active();

                    if (account != null) {
                        accounts.remove(account.id());
                        System.out.println("Signed out " + account.name());
                    }
                }
                case "worlds" -> Worlds.list(settings.gameDir()).forEach(world -> System.out.println(
                        world.name() + "  (" + world.backups() + " backups"
                                + (world.needsAttention() ? ", missing content from " + world.missingMods() : "")
                                + ")"));
                case "backup" -> {
                    Path world = world(settings, arguments);
                    WorldBackups.Backup made = WorldBackups.create(world, WorldBackups.MANUAL);
                    System.out.println("Backed up to " + made.file());
                }
                case "backups" -> WorldBackups.list(WorldBackups.folderFor(world(settings, arguments))).forEach(
                        backup -> System.out.println(backup.time() + "  " + backup.reason() + "  " + backup.file()));
                case "restore" -> {
                    Path zip = Path.of(arguments.remove(0));
                    Path world = world(settings, arguments);
                    WorldBackups.Backup backup = WorldBackups.list(zip.toAbsolutePath().getParent()).stream()
                            .filter(candidate -> candidate.file().equals(zip.toAbsolutePath()))
                            .findFirst().orElseThrow(() -> new IOException(zip + " is not an Octo backup"));
                    WorldBackups.Backup before = WorldBackups.restore(backup, world);
                    System.out.println("Restored " + world.getFileName()
                            + (before == null ? "" : "; the world it replaced is in " + before.file()));
                }
                case "update" -> {
                    Updates.Release release = Updates.latest();
                    System.out.println("Newest release: " + release.tag() + " (this launcher: " + build() + ")");
                    Updates.downloadLoader(release);
                    System.out.println("Updated the loader in: "
                            + Updates.updateInstallations(settings.gameDir(), log));
                }
                case "versions" -> Versions.list(settings.gameDir(), settings.showSnapshots).forEach(version ->
                        System.out.println(version.id() + (version.downloaded() ? "  downloaded" : "")
                                + (version.withOcto() ? "  Octo" : "")));
                default -> {
                    System.out.println("""
                            Octo Launcher %s (%s)

                              play [version]            start Minecraft with Octo (or open the Minecraft Launcher on it)
                              download [version]        download Minecraft and its Java (--repair checks every file)
                              sign-in                   sign in with a Microsoft account
                              sign-out                  sign the active account out
                              versions                  the Minecraft versions, and which have Octo
                              worlds                    the worlds, their backups, and any missing content
                              backup <world>            back a world up now
                              backups <world>           list a world's backups
                              restore <backup> <world>  put a backup back (the world now is backed up first)
                              update                    download the newest loader and install it everywhere
                            """.formatted(Installer.VERSION, build()));
                    return command.equals("help") ? 0 : 2;
                }
            }

            return 0;
        } catch (Exception e) {
            System.err.println("Failed: " + e.getMessage());
            return 1;
        }
    }

    /** Progress on one line of the terminal. */
    private static studios.milkdromeda.octo.launcher.game.GameFiles.Progress terminalProgress() {
        String[] last = { "" };
        long[] shown = { 0 };
        return (stage, done, total) -> {
            long now = System.currentTimeMillis();

            if (!stage.equals(last[0])) {
                System.out.print((last[0].isEmpty() ? "" : "\n") + stage);
                last[0] = stage;
            } else if (total > 0 && (now - shown[0] > 1000 || done == total)) {
                System.out.print("\r" + stage + "  " + done * 100 / total + "%  (" + done / 1048576 + " of "
                        + total / 1048576 + " MB)");
                shown[0] = now;
            }
        };
    }

    private static Path world(LauncherSettings settings, List<String> arguments) throws IOException {
        if (arguments.isEmpty()) {
            throw new IOException("name the world (its folder name in saves, or a path)");
        }

        Path named = Path.of(arguments.remove(0));
        return named.isAbsolute() || named.getNameCount() > 1 ? named
                : settings.gameDir().resolve("saves").resolve(named);
    }

    /** {@code build-<commit>} for a released build, {@code dev} otherwise. */
    public static String build() {
        String known = build;

        if (known == null) {
            String value = manifest("Octo-Build");
            known = value != null && !value.isBlank() ? value : "dev";
            build = known;
        }

        return known;
    }

    /** A value from this jar's manifest, or {@code null}. */
    public static String manifestValue(String name) {
        return manifest(name);
    }

    static String manifest(String name) {
        try (InputStream in = Launcher.class.getResourceAsStream("/META-INF/MANIFEST.MF")) {
            return in == null ? null : new Manifest(in).getMainAttributes().getValue(name);
        } catch (IOException e) {
            return null;
        }
    }

    static String userAgent() {
        return "OctoLauncher/" + Installer.VERSION + " (" + build() + "; +https://github.com/" + Updates.REPOSITORY
                + ")";
    }
}
