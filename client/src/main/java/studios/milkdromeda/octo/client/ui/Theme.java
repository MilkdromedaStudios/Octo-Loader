package studios.milkdromeda.octo.client.ui;

import studios.milkdromeda.octo.client.OctoClient;

/**
 * Octo Launcher's colours, in the game: ARGB, opaque unless said.
 *
 * <p>The look follows the theme the player picked in Octo Launcher — Octo,
 * Dark (graphite and frosted glass) or Light — read from the launcher's
 * settings when the game starts and each time Octo's menu opens, so a change
 * there shows here without restarting.
 */
public final class Theme {
    public static int WINDOW;
    public static int RAIL;
    public static int PANEL;
    public static int RAISED;
    public static int HOVER;
    public static int LINE;
    public static int TEXT;
    public static int MUTED;
    public static int FAINT;
    public static int GREEN;
    public static int AMBER;
    public static int RED;
    /** Highlights and hairlines over a surface: white on the dark themes, black on Light. */
    public static int INK;

    private static String look = "";
    private static long settingsStamp = Long.MIN_VALUE;

    static {
        apply("octo");
    }

    /** The theme in use: "octo", "dark" or "light". */
    public static String look() {
        return look;
    }

    public static boolean glass() {
        return look.equals("dark");
    }

    public static boolean light() {
        return look.equals("light");
    }

    /** Takes on a theme by its name in the launcher's settings; Octo for anything else. */
    public static void apply(String id) {
        String chosen = id == null ? "octo" : id.toLowerCase(java.util.Locale.ROOT);

        switch (chosen) {
            case "dark" -> {
                WINDOW = 0xE80C0D10;
                RAIL = 0x14FFFFFF;
                PANEL = 0x1AFFFFFF;
                RAISED = 0x26FFFFFF;
                HOVER = 0x36FFFFFF;
                LINE = 0x2AFFFFFF;
                TEXT = 0xFFF2F3F5;
                MUTED = 0xFFA3A8B2;
                FAINT = 0xFF6E737D;
                GREEN = 0xFF3BC980;
                AMBER = 0xFFF2A73B;
                RED = 0xFFF06262;
                INK = 0xFFFFFFFF;
            }
            case "light" -> {
                WINDOW = 0xF5F3F4F6;
                RAIL = 0xFFFAFAFB;
                PANEL = 0xFFFFFFFF;
                RAISED = 0xFFF0F1F4;
                HOVER = 0xFFE6E8EC;
                LINE = 0xFFE0E3E8;
                TEXT = 0xFF16181D;
                MUTED = 0xFF585E69;
                FAINT = 0xFF8C929C;
                GREEN = 0xFF1A9355;
                AMBER = 0xFFB86E00;
                RED = 0xFFD13645;
                INK = 0xFF000000;
            }
            default -> {
                chosen = "octo";
                WINDOW = 0xFF0A0C11;
                RAIL = 0xFF0E1016;
                PANEL = 0xFF13161E;
                RAISED = 0xFF1A1E28;
                HOVER = 0xFF222733;
                LINE = 0xFF242935;
                TEXT = 0xFFEEF0F6;
                MUTED = 0xFF9AA2B4;
                FAINT = 0xFF626A7C;
                GREEN = 0xFF3DDC97;
                AMBER = 0xFFFFB84D;
                RED = 0xFFFF5C6C;
                INK = 0xFFFFFFFF;
            }
        }

        look = chosen;
    }

    /**
     * Follows the theme chosen in Octo Launcher: its settings file, read again
     * only when it has changed. {@code -Docto.theme} wins, for a game started
     * some other way.
     */
    public static void follow() {
        String forced = System.getProperty("octo.theme");

        if (forced != null && !forced.isBlank()) {
            if (!forced.equalsIgnoreCase(look)) {
                apply(forced);
            }

            return;
        }

        java.nio.file.Path settings = java.nio.file.Path.of(System.getProperty("user.home"), ".octo-launcher",
                "settings.json");

        try {
            long stamp = java.nio.file.Files.getLastModifiedTime(settings).toMillis();

            if (stamp == settingsStamp) {
                return;
            }

            settingsStamp = stamp;
            com.google.gson.JsonObject json = com.google.gson.JsonParser
                    .parseString(java.nio.file.Files.readString(settings)).getAsJsonObject();
            apply(json.has("theme") ? json.get("theme").getAsString() : "octo");
        } catch (Exception e) {
            // No launcher settings (the game was not started from Octo Launcher): as it is.
        }
    }

    /** The accents a player can choose, with the second colour each fades into. */
    public static final int[][] ACCENTS = {
            { 0xFF7C5CFF, 0xFF00B2FF },
            { 0xFF00B2FF, 0xFF3DDC97 },
            { 0xFF3DDC97, 0xFF00B2FF },
            { 0xFFFFB84D, 0xFFFF5C6C },
            { 0xFFE05CFF, 0xFF7C5CFF },
    };
    public static final String[] ACCENT_NAMES = { "Octo", "Ocean", "Mint", "Sunset", "Orchid" };

    private Theme() {
    }

    /** The accent: the player's choice on Octo; the one calm blue of Dark and Light. */
    public static int accent() {
        return switch (look) {
            case "dark" -> 0xFF5E8BFF;
            case "light" -> 0xFF2F5BE0;
            default -> ACCENTS[OctoClient.config().accent][0];
        };
    }

    public static int accentEnd() {
        return switch (look) {
            case "dark" -> 0xFF789FFF;
            case "light" -> 0xFF4575F0;
            default -> ACCENTS[OctoClient.config().accent][1];
        };
    }

    /** A colour with its alpha replaced, 0–255. */
    public static int alpha(int colour, int alpha) {
        return (colour & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    /** Between two colours, by {@code amount} from 0 to 1, alpha included. */
    public static int mix(int from, int to, float amount) {
        float t = Math.max(0, Math.min(1, amount));
        int a = Math.round(((from >>> 24) & 0xFF) * (1 - t) + ((to >>> 24) & 0xFF) * t);
        int r = Math.round(((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
        int g = Math.round(((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
        int b = Math.round((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
