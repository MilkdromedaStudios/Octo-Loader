package studios.milkdromeda.octo.launcher;

import java.io.IOException;
import java.nio.file.Path;

import studios.milkdromeda.octo.common.WorldBackups;
import studios.milkdromeda.octo.installer.ClientInstaller;

/** What pressing Play does, up to the official launcher taking over. */
final class Play {
    private Play() {
    }

    /**
     * Installs or refreshes Octo for the version, backs up the world played
     * last, selects the Octo installation and opens the official launcher.
     *
     * @return what was done, in a sentence
     */
    static String prepareAndOpen(LauncherSettings settings, String version, ClientInstaller.Log log)
            throws IOException {
        Path game = settings.gameDir();

        if (!OfficialLauncher.hasRunIn(game)) {
            throw new IOException("the Minecraft Launcher has not been run in " + game
                    + " yet; open it once and sign in, then press Play here");
        }

        String chosen = version != null && !version.isBlank() ? version
                : !settings.selectedVersion.isBlank() ? settings.selectedVersion : Versions.latestRelease();

        if (chosen == null) {
            throw new IOException("no Minecraft version chosen, and Mojang's version list could not be read");
        }

        // Always installed again: a newer loader downloaded since goes in, and
        // a profile the player deleted comes back.
        String versionId = Updates.installer(game, log).install(chosen);

        if (settings.backupBeforeLaunch) {
            Worlds.World last = Worlds.lastPlayed(game);

            if (last != null) {
                WorldBackups.Backup made = WorldBackups.create(last.folder(), WorldBackups.BEFORE_LAUNCH);
                WorldBackups.prune(WorldBackups.folderFor(last.folder()), settings.backupsToKeep);
                log.info("backed up " + last.name() + " (" + made.size() / 1024 + " KB)");
            }
        }

        OfficialLauncher.select(game, "octo-loader-" + chosen);
        OfficialLauncher.open(OfficialLauncher.find(settings.officialLauncher), game);
        return "Opened the Minecraft Launcher with \"Octo Loader " + chosen + "\" (" + versionId
                + ") selected: sign in there if asked, then press Play.";
    }
}
