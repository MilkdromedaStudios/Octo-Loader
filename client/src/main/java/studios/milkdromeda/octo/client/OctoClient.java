package studios.milkdromeda.octo.client;

import org.slf4j.Logger;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

import studios.milkdromeda.octo.client.hud.Minimap;
import studios.milkdromeda.octo.client.hud.Stats;
import studios.milkdromeda.octo.client.music.MusicPlayer;
import studios.milkdromeda.octo.client.ui.OctoMenuScreen;

/**
 * Octo Client: the client mod Octo Launcher plays with. It changes how the
 * game looks and adds what players expect of a client — zoom, a minimap,
 * HUD loadouts, their own music, frame-rate savers — all set in one menu,
 * opened with Right Shift or the Octo button on the title and pause screens.
 */
public final class OctoClient implements ClientModInitializer {
    public static final Logger LOG = LogUtils.getLogger();

    private static Config config;
    private static boolean menuKeyWasDown;

    @Override
    public void onInitializeClient() {
        config();
        LOG.info("Octo Client ready: {} opens its menu", keyName(config.menuKey));
    }

    public static Config config() {
        if (config == null) {
            config = Config.load(Minecraft.getInstance().gameDirectory.toPath().resolve("config")
                    .resolve("octo-client.json"));
        }

        return config;
    }

    /** Every game tick, from {@link studios.milkdromeda.octo.client.mixin.MinecraftMixin}. */
    public static void tick(Minecraft minecraft) {
        Config settings = config();
        boolean noScreen = minecraft.gui.screen() == null;
        boolean menuKeyDown = noScreen && InputConstants.isKeyDown(settings.menuKey);

        if (menuKeyDown && !menuKeyWasDown) {
            minecraft.gui.setScreen(new OctoMenuScreen(null));
        }

        menuKeyWasDown = menuKeyDown;
        Stats.tick(minecraft);
        Minimap.tick(minecraft);
        MusicPlayer.get().tick(minecraft);
    }

    /** A key's name for the menu, from its SDL scancode. */
    public static String keyName(int scancode) {
        try {
            String name = org.lwjgl.sdl.SDLKeyboard.SDL_GetScancodeName(scancode);

            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (Throwable e) {
            // Before SDL is up, or a scancode with no name.
        }

        return "Key " + scancode;
    }
}
