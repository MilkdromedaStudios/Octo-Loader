package studios.milkdromeda.octo.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import studios.milkdromeda.octo.common.WorldBackups;
import studios.milkdromeda.octo.installer.ClientInstaller;
import studios.milkdromeda.octo.installer.Installer;
import studios.milkdromeda.octo.launcher.auth.Account;
import studios.milkdromeda.octo.launcher.auth.Accounts;
import studios.milkdromeda.octo.launcher.auth.MicrosoftAuth;
import studios.milkdromeda.octo.launcher.game.GameFiles;
import studios.milkdromeda.octo.launcher.game.GameLaunch;
import studios.milkdromeda.octo.launcher.game.JavaRuntimes;
import studios.milkdromeda.octo.launcher.game.Rules;
import studios.milkdromeda.octo.launcher.game.VersionProfile;

/** What pressing Play does: from a version to the game running with Octo. */
public final class Play {
    private Play() {
    }

    /** A game Octo started; {@code withOcto} is false for a version too old for Octo Loader. */
    public record Running(Process process, String version, Path log, boolean withOcto) {
    }

    /** What is ready to start: the version to launch, and whether it is Octo's. */
    record Prepared(String versionId, boolean withOcto) {
    }

    /**
     * The Microsoft app registration sign-in uses: one set for this run, the
     * one this build was made with, or, for a build made without one, the
     * player's own from Settings.
     */
    public static String clientId(LauncherSettings settings) {
        String[] candidates = { System.getenv("OCTO_MSA_CLIENT_ID"), System.getProperty("octo.msa.clientId"),
                Launcher.manifest("Octo-Msa-Client-Id"), settings.microsoftClientId };

        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }

        return null;
    }

    /** Whether Play starts the game from Octo, rather than through the Minecraft Launcher. */
    public static boolean launchesItself(LauncherSettings settings) {
        return !settings.useOfficialLauncher && clientId(settings) != null;
    }

    public static Accounts accounts() {
        return new Accounts(LauncherSettings.home());
    }

    static String chosen(LauncherSettings settings, String version) throws IOException {
        String chosen = version != null && !version.isBlank() ? version
                : !settings.selectedVersion.isBlank() ? settings.selectedVersion : Versions.latestRelease();

        if (chosen == null) {
            throw new IOException("no Minecraft version chosen, and Mojang's version list could not be read");
        }

        return chosen;
    }

    /** The signed-in account with a session good for a game, refreshed if it needs to be. */
    public static Account session(LauncherSettings settings, Accounts accounts) throws IOException {
        Account account = accounts.active();

        if (account == null) {
            throw new IOException("Sign in with your Microsoft account first.");
        }

        if (!account.fresh()) {
            String clientId = clientId(settings);

            if (clientId == null) {
                throw new IOException("this build of Octo Launcher has no Microsoft sign-in");
            }

            account = new MicrosoftAuth(clientId).refresh(account);
            accounts.put(account);
        }

        return account;
    }

    /**
     * Downloads Minecraft and its Java for a version, installs Octo for it,
     * and gets everything ready to start.
     *
     * @param verify check every file already there against its SHA-1 (a repair)
     * @return the version id to start: Octo's installation, or the game itself
     *         for a version too old for Octo
     */
    public static String download(LauncherSettings settings, String version, boolean verify,
            GameFiles.Progress progress) throws IOException {
        return prepare(settings, version, verify, progress).versionId();
    }

    static Prepared prepare(LauncherSettings settings, String version, boolean verify, GameFiles.Progress progress)
            throws IOException {
        Path game = settings.gameDir();
        String chosen = chosen(settings, version);
        progress.update("Getting Minecraft " + chosen, 0, 0);
        GameFiles.versionFiles(game, chosen, Versions::entry);
        boolean withOcto = VersionProfile.load(game, chosen).supportsOcto();
        String versionId = withOcto ? Updates.installer(game, message -> { }).install(chosen) : chosen;
        VersionProfile profile = VersionProfile.load(game, versionId);
        Rules.Platform platform = Rules.Platform.current();
        GameFiles.fetch(game, profile, platform, verify, progress);

        if (settings.javaPath.isBlank()) {
            JavaRuntimes.ensure(LauncherSettings.runtimes(), profile.javaComponent(withOcto), platform, verify,
                    progress);
        }

        return new Prepared(versionId, withOcto);
    }

    /**
     * Gets everything ready and starts Minecraft with Octo, signed in as the
     * active account.
     */
    public static Running launch(LauncherSettings settings, Accounts accounts, String version,
            GameFiles.Progress progress) throws IOException {
        Path game = settings.gameDir();
        Account account = session(settings, accounts);
        Prepared prepared = prepare(settings, version, false, progress);
        String chosen = chosen(settings, version);
        VersionProfile profile = VersionProfile.load(game, prepared.versionId());
        Rules.Platform platform = Rules.Platform.current();
        Path java = settings.javaPath.isBlank()
                ? JavaRuntimes.ensure(LauncherSettings.runtimes(), profile.javaComponent(prepared.withOcto()),
                        platform, false, progress)
                : Path.of(settings.javaPath);

        backUpLastWorld(settings, message -> { });
        progress.update("Starting Minecraft", 0, 0);
        Path natives = GameFiles.unpackNatives(game, profile, platform);
        List<String> extra = new ArrayList<>();

        for (String option : settings.jvmArguments.trim().split("\\s+")) {
            if (!option.isBlank()) {
                extra.add(option);
            }
        }

        GameLaunch.Options options = new GameLaunch.Options(java, Math.max(512, settings.memoryMb), extra, 0, 0,
                "octo-launcher", Installer.VERSION);
        GameLaunch.Player player = new GameLaunch.Player(account.name(), account.id(), account.accessToken(), null);
        List<String> command = new ArrayList<>(GameLaunch.command(game, profile, platform, player, options,
                natives));

        // Octo Client, for the versions it is built for: a mods folder of its own
        // beside the player's, read by Octo Loader and never shown to the game.
        if (prepared.withOcto() && settings.octoClient && OctoClientMod.supports(chosen)) {
            Path client = OctoClientMod.install();
            command.addAll(List.of("--modsDir", game.resolve("mods").toAbsolutePath().toString(), "--modsDir",
                    client.toAbsolutePath().toString()));
        }

        Path logs = LauncherSettings.home().resolve("logs");
        Path log = logs.resolve("game.log");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("last-launch.txt"), GameLaunch.printable(command, account.accessToken())
                + "\n", StandardCharsets.UTF_8);
        return new Running(GameLaunch.start(command, game, log), chosen, log, prepared.withOcto());
    }

    private static void backUpLastWorld(LauncherSettings settings, ClientInstaller.Log log) throws IOException {
        if (!settings.backupBeforeLaunch) {
            return;
        }

        Worlds.World last = Worlds.lastPlayed(settings.gameDir());

        if (last != null) {
            WorldBackups.Backup made = WorldBackups.create(last.folder(), WorldBackups.BEFORE_LAUNCH);
            WorldBackups.prune(WorldBackups.folderFor(last.folder()), settings.backupsToKeep);
            log.info("backed up " + last.name() + " (" + made.size() / 1024 + " KB)");
        }
    }

    /**
     * Installs or refreshes Octo for the version, backs up the world played
     * last, selects the Octo installation and opens the official launcher.
     *
     * @return what was done, in a sentence
     */
    public static String prepareAndOpen(LauncherSettings settings, String version, ClientInstaller.Log log)
            throws IOException {
        Path game = settings.gameDir();

        if (!OfficialLauncher.hasRunIn(game)) {
            throw new IOException("the Minecraft Launcher has not been run in " + game
                    + " yet; open it once and sign in, then press Play here");
        }

        String chosen = chosen(settings, version);

        // Always installed again: a newer loader downloaded since goes in, and
        // a profile the player deleted comes back.
        String versionId = Updates.installer(game, log).install(chosen);
        backUpLastWorld(settings, log);
        OfficialLauncher.select(game, "octo-loader-" + chosen);
        OfficialLauncher.open(OfficialLauncher.find(settings.officialLauncher), game);
        return "Opened the Minecraft Launcher with \"Octo Loader " + chosen + "\" (" + versionId
                + ") selected: sign in there if asked, then press Play.";
    }

    /** Removes one downloaded version of Minecraft (and Octo's installation for it); libraries stay. */
    public static void deleteVersion(LauncherSettings settings, String version) throws IOException {
        Path versions = settings.gameDir().resolve("versions");

        for (String id : List.of(ClientInstaller.ARTIFACT + "-" + version, version)) {
            Path folder = versions.resolve(id).normalize();

            if (folder.startsWith(versions) && !folder.equals(versions) && Files.isDirectory(folder)) {
                try (var walk = Files.walk(folder)) {
                    for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        Files.delete(path);
                    }
                }
            }
        }
    }
}
