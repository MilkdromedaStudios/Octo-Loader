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

    /**
     * A game Octo started; {@code withOcto} is false for a version too old for
     * Octo Loader and for a plain game, whose loader {@code loader} names.
     */
    public record Running(Process process, String version, Path log, boolean withOcto,
            PlainLoaders.Loader loader) {
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
        PlainLoaders.Loader loader = settings.loader();

        if (loader.plain()) {
            return preparePlain(settings, loader, chosen, verify, progress);
        }

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
     * Minecraft as one loader ships it: the loader installed for the version,
     * then its files and Java fetched like any version's.
     */
    private static Prepared preparePlain(LauncherSettings settings, PlainLoaders.Loader loader, String chosen,
            boolean verify, GameFiles.Progress progress) throws IOException {
        Path game = settings.gameDir();
        Rules.Platform platform = Rules.Platform.current();
        VersionProfile base = VersionProfile.load(game, chosen);
        // Forge's and NeoForge's installers run on the Java the game itself wants.
        Path java = settings.javaPath.isBlank()
                ? JavaRuntimes.ensure(LauncherSettings.runtimes(), base.javaComponent(false), platform, verify,
                        progress)
                : Path.of(settings.javaPath);

        progress.update("Installing " + loader.label + " for Minecraft " + chosen, 0, 0);
        String versionId = PlainLoaders.install(game, loader, chosen, java, message -> { });
        VersionProfile profile = VersionProfile.load(game, versionId);
        GameFiles.fetch(game, profile, platform, verify, progress);

        if (settings.javaPath.isBlank()) {
            JavaRuntimes.ensure(LauncherSettings.runtimes(), profile.javaComponent(false), platform, verify,
                    progress);
        }

        Files.createDirectories(PlainLoaders.instance(game, loader, chosen));
        return new Prepared(versionId, false);
    }

    /**
     * Gets everything ready and starts Minecraft with Octo, signed in as the
     * active account; or, with a plain loader chosen, as that loader ships it,
     * in its own folder.
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

        PlainLoaders.Loader loader = settings.loader();
        Path play = loader.plain() ? PlainLoaders.instance(game, loader, chosen) : game;
        backUpLastWorld(settings, play, message -> { });
        progress.update("Starting Minecraft", 0, 0);
        Path natives = GameFiles.unpackNatives(game, profile, platform);
        List<String> extra = new ArrayList<>();

        if (!loader.plain()) {
            // Where Octo Client is: Octo Loader would otherwise look under the game's own home folder.
            extra.add("-Docto.launcherHome=" + LauncherSettings.home().toAbsolutePath());
        }

        for (String option : settings.jvmArguments.trim().split("\\s+")) {
            if (!option.isBlank()) {
                extra.add(option);
            }
        }

        GameLaunch.Options options = new GameLaunch.Options(java, Math.max(512, settings.memoryMb), extra, 0, 0,
                "octo-launcher", Installer.VERSION);
        GameLaunch.Player player = new GameLaunch.Player(account.name(), account.id(), account.accessToken(), null);
        if (!loader.plain()) {
            // Octo Client in its folder, or out of it, as the setting says: Octo Loader
            // finds it there for the version it is built for.
            OctoClientMod.sync(settings);
        }

        List<String> command = GameLaunch.command(game, play, profile, platform, player, options, natives);

        Path logs = LauncherSettings.home().resolve("logs");
        Path log = logs.resolve("game.log");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("last-launch.txt"), GameLaunch.printable(command, account.accessToken())
                + "\n", StandardCharsets.UTF_8);
        return new Running(GameLaunch.start(command, play, log), chosen, log, prepared.withOcto(), loader);
    }

    private static void backUpLastWorld(LauncherSettings settings, Path play, ClientInstaller.Log log)
            throws IOException {
        if (!settings.backupBeforeLaunch) {
            return;
        }

        Worlds.World last = Worlds.lastPlayed(play);

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
        PlainLoaders.Loader loader = settings.loader();

        if (loader.plain()) {
            return preparePlainAndOpen(settings, loader, chosen, log);
        }

        // Always installed again: a newer loader downloaded since goes in, and
        // a profile the player deleted comes back.
        String versionId = Updates.installer(game, log).install(chosen);
        OctoClientMod.sync(settings);
        backUpLastWorld(settings, game, log);
        OfficialLauncher.select(game, "octo-loader-" + chosen);
        OfficialLauncher.open(OfficialLauncher.find(settings.officialLauncher), game);
        return "Opened the Minecraft Launcher with \"Octo Loader " + chosen + "\" (" + versionId
                + ") selected: sign in there if asked, then press Play.";
    }

    /**
     * A plain loader through the Minecraft Launcher: the loader installed, and
     * a profile of Octo's that starts it in its own folder, selected.
     */
    private static String preparePlainAndOpen(LauncherSettings settings, PlainLoaders.Loader loader, String chosen,
            ClientInstaller.Log log) throws IOException {
        Path game = settings.gameDir();
        Prepared prepared = prepare(settings, chosen, false, (stage, done, total) -> { });
        Path play = PlainLoaders.instance(game, loader, chosen);
        backUpLastWorld(settings, play, log);
        String key = "octo-plain-" + loader.key + "-" + chosen;
        OfficialLauncher.addProfile(game, key, loader.label + " " + chosen, prepared.versionId(), play);
        OfficialLauncher.select(game, key);
        OfficialLauncher.open(OfficialLauncher.find(settings.officialLauncher), game);
        return "Opened the Minecraft Launcher with \"" + loader.label + " " + chosen + "\" selected, without Octo: "
                + "sign in there if asked, then press Play.";
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
