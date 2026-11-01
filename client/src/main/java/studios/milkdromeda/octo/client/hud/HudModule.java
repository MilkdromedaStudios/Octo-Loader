package studios.milkdromeda.octo.client.hud;

/** One thing the HUD can show. */
public enum HudModule {
    FPS("FPS", "Frames per second."),
    COORDINATES("Coordinates", "Where you are: X, Y and Z."),
    DIRECTION("Direction", "The way you face, as a compass point."),
    BIOME("Biome", "The biome you stand in."),
    CLOCK("Clock", "The time where you are."),
    SPEED("Speed", "How fast you move, in blocks a second."),
    PING("Ping", "Your connection's delay to the server."),
    CPS("CPS", "Clicks per second, left and right."),
    MEMORY("Memory", "How much memory the game is using."),
    ARMOR("Armour", "Your armour and held item, with how worn they are."),
    KEYSTROKES("Keystrokes", "The movement keys and mouse buttons you press.");

    public final String title;
    public final String description;

    HudModule(String title, String description) {
        this.title = title;
        this.description = description;
    }

    /** Shown as a line of text in the HUD's panel. */
    public boolean isText() {
        return this != ARMOR && this != KEYSTROKES;
    }
}
