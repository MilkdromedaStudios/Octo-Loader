package studios.milkdromeda.octo.launcher.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import studios.milkdromeda.octo.installer.Theme;

/**
 * The launcher's look, in one of three themes the player picks in Settings:
 *
 * <ul>
 * <li><b>Octo</b>: ink-dark surfaces and the mark's violet-to-cyan accent, as
 * Octo has always looked;</li>
 * <li><b>Dark</b>: graphite behind panels of frosted glass, a single calm
 * blue, no glow;</li>
 * <li><b>Light</b>: paper-white surfaces, soft grey lines and the same calm
 * blue.</li>
 * </ul>
 *
 * Colours are read when a component paints, so the window is rebuilt when
 * the theme changes. Octo Client reads the same choice and follows it.
 * Everything is drawn, so it looks the same on every desktop.
 */
public final class Style {
    /** A theme, by the name kept in the settings. */
    public enum Look {
        OCTO("octo", "Octo"), DARK("dark", "Dark"), LIGHT("light", "Light");

        public final String id;
        public final String label;

        Look(String id, String label) {
            this.id = id;
            this.label = label;
        }

        /** The theme of a settings value, Octo for anything else. */
        public static Look of(String id) {
            for (Look look : values()) {
                if (look.id.equalsIgnoreCase(String.valueOf(id))) {
                    return look;
                }
            }

            return OCTO;
        }
    }

    public static Color WINDOW;
    public static Color RAIL;
    public static Color PANEL;
    public static Color RAISED;
    public static Color HOVER;
    public static Color LINE;

    public static Color TEXT;
    public static Color MUTED;
    public static Color FAINT;

    /** The accent and the colour it fades into: Octo's violet and cyan, the others' one blue. */
    public static Color VIOLET;
    public static Color CYAN;
    public static Color GREEN;
    public static Color AMBER;
    public static Color RED;

    /** What highlights and hairlines are drawn in over a surface: white on the dark themes, black on Light. */
    public static Color INK;
    /** The home banner's two corners. */
    public static Color HERO_FROM;
    public static Color HERO_TO;

    private static Look look = Look.OCTO;

    static {
        apply(Look.OCTO);
    }

    private Style() {
    }

    public static Look look() {
        return look;
    }

    /** Whether surfaces are glass, translucent over the window behind them. */
    public static boolean glass() {
        return look == Look.DARK;
    }

    public static boolean light() {
        return look == Look.LIGHT;
    }

    /** Takes on a theme; what is painted after this paints in it. */
    public static void apply(Look theme) {
        look = theme;

        switch (theme) {
            case DARK -> {
                WINDOW = new Color(0x0C, 0x0D, 0x10);
                RAIL = new Color(255, 255, 255, 9);
                PANEL = new Color(255, 255, 255, 13);
                RAISED = new Color(255, 255, 255, 22);
                HOVER = new Color(255, 255, 255, 34);
                LINE = new Color(255, 255, 255, 24);
                TEXT = new Color(0xF2, 0xF3, 0xF5);
                MUTED = new Color(0xA3, 0xA8, 0xB2);
                FAINT = new Color(0x6E, 0x73, 0x7D);
                VIOLET = new Color(0x5E, 0x8B, 0xFF);
                CYAN = new Color(0x78, 0x9F, 0xFF);
                GREEN = new Color(0x3B, 0xC9, 0x80);
                AMBER = new Color(0xF2, 0xA7, 0x3B);
                RED = new Color(0xF0, 0x62, 0x62);
                INK = Color.WHITE;
                HERO_FROM = new Color(0x1A, 0x1C, 0x22);
                HERO_TO = new Color(0x0F, 0x11, 0x15);
            }
            case LIGHT -> {
                WINDOW = new Color(0xF3, 0xF4, 0xF6);
                RAIL = new Color(0xFA, 0xFA, 0xFB);
                PANEL = Color.WHITE;
                RAISED = new Color(0xF0, 0xF1, 0xF4);
                HOVER = new Color(0xE6, 0xE8, 0xEC);
                LINE = new Color(0xE0, 0xE3, 0xE8);
                TEXT = new Color(0x16, 0x18, 0x1D);
                MUTED = new Color(0x58, 0x5E, 0x69);
                FAINT = new Color(0x8C, 0x92, 0x9C);
                VIOLET = new Color(0x2F, 0x5B, 0xE0);
                CYAN = new Color(0x45, 0x75, 0xF0);
                GREEN = new Color(0x1A, 0x93, 0x55);
                AMBER = new Color(0xB8, 0x6E, 0x00);
                RED = new Color(0xD1, 0x36, 0x45);
                INK = Color.BLACK;
                HERO_FROM = new Color(0xE4, 0xEA, 0xF8);
                HERO_TO = new Color(0xF6, 0xF7, 0xFA);
            }
            default -> {
                WINDOW = new Color(0x0A, 0x0C, 0x11);
                RAIL = new Color(0x0E, 0x10, 0x16);
                PANEL = new Color(0x13, 0x16, 0x1E);
                RAISED = new Color(0x1A, 0x1E, 0x28);
                HOVER = new Color(0x22, 0x27, 0x33);
                LINE = new Color(0x24, 0x29, 0x35);
                TEXT = new Color(0xEE, 0xF0, 0xF6);
                MUTED = new Color(0x9A, 0xA2, 0xB4);
                FAINT = new Color(0x62, 0x6A, 0x7C);
                VIOLET = new Color(0x7C, 0x5C, 0xFF);
                CYAN = new Color(0x00, 0xB2, 0xFF);
                GREEN = new Color(0x3D, 0xDC, 0x97);
                AMBER = new Color(0xFF, 0xB8, 0x4D);
                RED = new Color(0xFF, 0x5C, 0x6C);
                INK = Color.WHITE;
                HERO_FROM = new Color(0x1C, 0x13, 0x42);
                HERO_TO = new Color(0x06, 0x1B, 0x30);
            }
        }
    }

    /**
     * The window's own backdrop. Octo and Light are one colour; Dark lays two
     * faint, wide washes of light under its glass, which is what makes glass
     * read as glass.
     */
    public static void backdrop(Graphics2D g, int width, int height) {
        g.setColor(WINDOW);
        g.fillRect(0, 0, width, height);

        if (glass()) {
            wash(g, width * 0.18f, height * 0.02f, Math.max(width, height) * 0.7f, alpha(VIOLET, 34));
            wash(g, width * 0.98f, height * 0.95f, Math.max(width, height) * 0.6f, new Color(0x9A, 0xA4, 0xB8, 22));
        }
    }

    private static void wash(Graphics2D g, float x, float y, float radius, Color colour) {
        g.setPaint(new java.awt.RadialGradientPaint(new java.awt.geom.Point2D.Float(x, y), radius,
                new float[] { 0f, 1f }, new Color[] { colour, alpha(colour, 0) }));
        g.fill(new java.awt.geom.Rectangle2D.Float(x - radius, y - radius, radius * 2, radius * 2));
    }

    private static final Font REGULAR = face("Inter-Regular.ttf");
    private static final Font SEMIBOLD = face("Inter-SemiBold.ttf");
    private static final Font BOLD = face("Inter-Bold.ttf");
    private static final Font DISPLAY = face("InterDisplay-Bold.ttf");

    /** A face bundled with the app (Inter, under the SIL Open Font License), or the system's own if it will not load. */
    private static Font face(String file) {
        try (java.io.InputStream in = Style.class.getResourceAsStream("/octo/fonts/" + file)) {
            if (in != null) {
                Font font = Font.createFont(Font.TRUETYPE_FONT, in);
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(font);
                return font;
            }
        } catch (Exception e) {
            // The system's face, then.
        }

        return null;
    }

    /** Text: regular, or semi-bold for {@link Font#BOLD}. */
    public static Font font(int style, float size) {
        Font face = style == Font.BOLD ? SEMIBOLD : REGULAR;
        return face == null ? Theme.ui(style, size) : face.deriveFont(size);
    }

    /** Bold, for buttons and figures. */
    public static Font strong(float size) {
        return BOLD == null ? Theme.ui(Font.BOLD, size) : BOLD.deriveFont(size);
    }

    /** The display cut, for page titles and the banner. */
    public static Font display(float size) {
        return DISPLAY == null ? Theme.ui(Font.BOLD, size) : DISPLAY.deriveFont(size);
    }

    public static Graphics2D smooth(Graphics graphics) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        canvas.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        canvas.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        return canvas;
    }

    /** The accent, left to right across a width. */
    public static GradientPaint accent(float x, float width) {
        return new GradientPaint(x, 0, VIOLET, x + width, 0, CYAN);
    }

    public static Color alpha(Color colour, int alpha) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), alpha);
    }

    /** Between two colours, alpha included, so glass mixed with a colour stays glass. */
    public static Color mix(Color from, Color to, float amount) {
        float keep = 1 - amount;
        return new Color(Math.round(from.getRed() * keep + to.getRed() * amount),
                Math.round(from.getGreen() * keep + to.getGreen() * amount),
                Math.round(from.getBlue() * keep + to.getBlue() * amount),
                Math.round(from.getAlpha() * keep + to.getAlpha() * amount));
    }

    /** Hex for HTML text in labels. */
    public static String hex(Color colour) {
        return String.format("#%02X%02X%02X", colour.getRed(), colour.getGreen(), colour.getBlue());
    }
}
