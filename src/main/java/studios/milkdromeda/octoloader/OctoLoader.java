package studios.milkdromeda.octoloader;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OctoLoader implements ModInitializer {
    public static final String MOD_ID = "octoloader";
    public static final Logger LOGGER = LoggerFactory.getLogger("OctoLoader");

    @Override
    public void onInitialize() {
        LOGGER.info("Octo Loader initialized");
        studios.milkdromeda.octoloader.shims.neoforge.NeoForgeRuntime.initAll();
    }
}
