package studios.milkdromeda.octoloader.bootstrap;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Earliest supported entrypoint. Discovery and translation of foreign mods is
 * kicked off from here (or earlier, via the injection hook) in later milestones.
 */
public class OctoPreLaunch implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger("OctoLoader/PreLaunch");

    @Override
    public void onPreLaunch() {
        LOGGER.info("Octo Loader pre-launch: inspecting mods directory");
        studios.milkdromeda.octoloader.OctoRuntime.runFromPreLaunch();
    }
}
