package studios.milkdromeda.octo.client.hud;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import studios.milkdromeda.octo.client.Config;

/** A set of HUD modules for a way of playing, picked in one go. */
public enum Loadout {
    MINIMAL("Minimal", "Just your frame rate.", EnumSet.of(HudModule.FPS)),
    ESSENTIALS("Essentials", "Frame rate, where you are, which way you face, and the time.",
            EnumSet.of(HudModule.FPS, HudModule.COORDINATES, HudModule.DIRECTION, HudModule.CLOCK)),
    PVP("PvP", "Clicks per second, ping, your armour, effects and the keys you press.",
            EnumSet.of(HudModule.FPS, HudModule.CPS, HudModule.PING, HudModule.ARMOR, HudModule.KEYSTROKES,
                    HudModule.EFFECTS)),
    BUILDER("Builder", "Coordinates, facing, biome and the time.",
            EnumSet.of(HudModule.COORDINATES, HudModule.DIRECTION, HudModule.BIOME, HudModule.CLOCK)),
    EXPLORER("Explorer", "Coordinates, facing, biome and how fast you travel.",
            EnumSet.of(HudModule.COORDINATES, HudModule.DIRECTION, HudModule.BIOME, HudModule.SPEED)),
    SURVIVAL("Survival", "The day, the light where you stand, your food, what you look at, and your effects.",
            EnumSet.of(HudModule.COORDINATES, HudModule.DAY, HudModule.LIGHT, HudModule.FOOD, HudModule.TARGET,
                    HudModule.EFFECTS)),
    SERVER("Server", "Ping, who is online, how long you have played, and the time.",
            EnumSet.of(HudModule.FPS, HudModule.PING, HudModule.PLAYERS, HudModule.SESSION, HudModule.CLOCK)),
    CUSTOM("Custom", "The modules you choose below.", EnumSet.noneOf(HudModule.class));

    public final String title;
    public final String description;
    private final Set<HudModule> modules;

    Loadout(String title, String description, Set<HudModule> modules) {
        this.title = title;
        this.description = description;
        this.modules = modules;
    }

    public static Loadout of(Config config) {
        try {
            return valueOf(config.loadout);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ESSENTIALS;
        }
    }

    /** The modules shown, the custom loadout's from the settings. */
    public Set<HudModule> modules(Config config) {
        if (this != CUSTOM) {
            return modules;
        }

        Set<HudModule> out = EnumSet.noneOf(HudModule.class);

        for (String name : config.customModules) {
            try {
                out.add(HudModule.valueOf(name));
            } catch (IllegalArgumentException e) {
                // A module from another version: left out.
            }
        }

        return out;
    }

    public static List<String> titles() {
        List<String> out = new ArrayList<>();

        for (Loadout loadout : values()) {
            out.add(loadout.title);
        }

        return out;
    }
}
