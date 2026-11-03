package studios.milkdromeda.octo.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * Everything the player sets in the Octo menu, kept in
 * {@code config/octo-client.json} and saved as it changes.
 */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ------------------------------------------------------------ look
    /** Octo's look for buttons and sliders, everywhere in the game. */
    public boolean modernWidgets = true;
    /** Softer, higher clicks for buttons. */
    public boolean softClicks = true;
    /** The accent colour: an index into {@link studios.milkdromeda.octo.client.ui.Theme#ACCENTS}. */
    public int accent = 0;

    // ------------------------------------------------------------- HUD
    public boolean hud = true;
    /** The loadout shown: one of {@link studios.milkdromeda.octo.client.hud.Loadout}'s names. */
    public String loadout = "ESSENTIALS";
    /** The modules of the custom loadout. */
    public List<String> customModules = new ArrayList<>(List.of("FPS", "COORDINATES", "DIRECTION", "CLOCK"));
    /** Soft panels behind the HUD's text. */
    public boolean hudPanels = true;

    // ------------------------------------------------------------ zoom
    public boolean zoom = true;
    /** The key held to zoom: an SDL scancode, C by default. */
    public int zoomKey = 6;
    /** How many times closer, from 2 to 12. */
    public int zoomLevel = 4;
    public boolean smoothZoom = true;
    /** The mouse wheel zooms further in and out while zooming. */
    public boolean scrollToZoom = true;

    /** Everything Octo draws on the HUD, in percent of Minecraft's own GUI size. */
    public int hudScale = 100;

    // --------------------------------------------------------- minimap
    public boolean minimap = true;
    /** From before the size was a slider: 0 small, 1 medium, 2 large. Read once into {@link #minimapPixels}. */
    public int minimapSize = 1;
    /** How big the minimap is on screen, in GUI pixels; 0 until set, then taken from {@link #minimapSize}. */
    public int minimapPixels = 0;
    /** Blocks per pixel: 0 is one, 1 is two, 2 is four. */
    public int minimapScale = 0;
    public boolean minimapCoordinates = true;
    /** What the maps show: 0 decides by where you are (smart altitude), 1 the surface, 2 caves. */
    public int minimapLayer = 0;
    /** The key that opens the big map: an SDL scancode, M by default. */
    public int mapKey = 16;
    /** The key that sets a waypoint where you stand: B by default. */
    public int waypointKey = 5;
    public boolean waypointsOnMap = true;
    /** Markers floating over waypoints in the world, with their distance. */
    public boolean waypointsInWorld = true;
    /** A waypoint where you last died. */
    public boolean deathWaypoint = true;

    // ----------------------------------------------------------- music
    /** Play the player's own music in place of Minecraft's. */
    public boolean music = false;
    public int musicVolume = 70;
    public boolean shuffle = true;
    public boolean nowPlaying = true;
    /** 0 shows the now-playing card when a song starts, 1 all the time. */
    public int nowPlayingStyle = 0;
    /** 0 at the bottom right, 1 at the top. */
    public int nowPlayingPlace = 0;
    /** The card's size, in percent. */
    public int nowPlayingScale = 100;

    // ----------------------------------------------------- performance
    /** A lower frame rate while the game is not the window in front. */
    public boolean dynamicFps = true;
    public int unfocusedFps = 15;
    /** Entities further than this are not drawn; 0 draws them at any distance. */
    public int entityDistance = 0;
    /** Players are drawn at any distance, even with an entity distance set. */
    public boolean alwaysShowPlayers = true;
    /** The share of particles that are made, in percent. */
    public int particles = 100;

    // ------------------------------------------------------------ keys
    /** The key that opens the Octo menu: Right Shift by default. */
    public int menuKey = 229;

    private transient Path file;

    public static Config load(Path file) {
        Config config = null;

        try {
            if (Files.isRegularFile(file)) {
                config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
            }
        } catch (IOException | JsonParseException e) {
            OctoClient.LOG.warn("could not read {}; starting from the defaults", file, e);
        }

        if (config == null) {
            config = new Config();
        }

        config.file = file;
        config.clamp();
        return config;
    }

    /** Values from an edited file kept in their ranges. */
    void clamp() {
        zoomLevel = Math.max(2, Math.min(12, zoomLevel));
        minimapSize = Math.max(0, Math.min(2, minimapSize));

        if (minimapPixels == 0) {
            minimapPixels = new int[] { 72, 96, 128 }[minimapSize];
        }

        minimapPixels = Math.max(64, Math.min(256, minimapPixels));
        hudScale = Math.max(50, Math.min(200, hudScale));
        nowPlayingScale = Math.max(60, Math.min(200, nowPlayingScale));
        minimapScale = Math.max(0, Math.min(2, minimapScale));
        musicVolume = Math.max(0, Math.min(100, musicVolume));
        unfocusedFps = Math.max(5, Math.min(60, unfocusedFps));
        entityDistance = Math.max(0, Math.min(256, entityDistance));
        particles = Math.max(0, Math.min(100, particles));
        accent = Math.max(0, Math.min(4, accent));

        if (customModules == null) {
            customModules = new ArrayList<>();
        }
    }

    public void save() {
        if (file == null) {
            return;
        }

        try {
            Files.createDirectories(file.getParent());
            Path part = file.resolveSibling(file.getFileName() + ".part");
            Files.writeString(part, GSON.toJson(this) + "\n", StandardCharsets.UTF_8);
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            OctoClient.LOG.warn("could not save {}", file, e);
        }
    }
}
