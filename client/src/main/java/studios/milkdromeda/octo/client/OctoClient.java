package studios.milkdromeda.octo.client;

import org.slf4j.Logger;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

import studios.milkdromeda.octo.client.hud.Minimap;
import studios.milkdromeda.octo.client.hud.OctoHud;
import studios.milkdromeda.octo.client.hud.Stats;
import studios.milkdromeda.octo.client.map.BigMapScreen;
import studios.milkdromeda.octo.client.map.MapCache;
import studios.milkdromeda.octo.client.map.Terrain;
import studios.milkdromeda.octo.client.map.Waypoints;
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
    private static boolean mapKeyWasDown;
    private static boolean waypointKeyWasDown;

    @Override
    public void onInitializeClient() {
        config();
        studios.milkdromeda.octo.client.ui.Theme.follow();
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

        boolean playing = noScreen && minecraft.player != null && minecraft.level != null;
        boolean mapKeyDown = playing && settings.minimap && InputConstants.isKeyDown(settings.mapKey);

        if (mapKeyDown && !mapKeyWasDown && !menuKeyDown) {
            minecraft.gui.setScreen(new BigMapScreen());
        }

        mapKeyWasDown = mapKeyDown;
        boolean waypointKeyDown = playing && InputConstants.isKeyDown(settings.waypointKey);

        if (waypointKeyDown && !waypointKeyWasDown) {
            var player = minecraft.player;
            var waypoint = Waypoints.add(minecraft, player.getBlockX(), player.getBlockY(), player.getBlockZ());
            OctoHud.toast(waypoint.name + " set at " + waypoint.x + ", " + waypoint.y + ", " + waypoint.z
                    + "  ·  " + keyName(settings.mapKey) + " for the map");
        }

        waypointKeyWasDown = waypointKeyDown;
        Stats.tick(minecraft);
        Terrain.tick(minecraft);
        Minimap.tick(minecraft);
        Waypoints.tick(minecraft);

        if (settings.minimap) {
            // A few chunks a tick for the big map, many while it is open.
            MapCache.tick(minecraft, minecraft.gui.screen() instanceof BigMapScreen ? 24 : 3);
        }

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
