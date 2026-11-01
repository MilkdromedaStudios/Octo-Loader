package studios.milkdromeda.octo.client.ui;

import studios.milkdromeda.octo.client.OctoClient;

/** Octo Launcher's colours, in the game: ARGB, opaque unless said. */
public final class Theme {
    public static final int WINDOW = 0xFF0A0C11;
    public static final int RAIL = 0xFF0E1016;
    public static final int PANEL = 0xFF13161E;
    public static final int RAISED = 0xFF1A1E28;
    public static final int HOVER = 0xFF222733;
    public static final int LINE = 0xFF242935;
    public static final int TEXT = 0xFFEEF0F6;
    public static final int MUTED = 0xFF9AA2B4;
    public static final int FAINT = 0xFF626A7C;
    public static final int GREEN = 0xFF3DDC97;
    public static final int AMBER = 0xFFFFB84D;
    public static final int RED = 0xFFFF5C6C;

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

    public static int accent() {
        return ACCENTS[OctoClient.config().accent][0];
    }

    public static int accentEnd() {
        return ACCENTS[OctoClient.config().accent][1];
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
