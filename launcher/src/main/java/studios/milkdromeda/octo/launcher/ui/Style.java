package studios.milkdromeda.octo.launcher.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import studios.milkdromeda.octo.installer.Theme;

/**
 * The client's look: ink-dark surfaces, the mark's violet-to-cyan as the one
 * accent, and soft rounded shapes. Everything is drawn, so it looks the same on
 * every desktop.
 */
public final class Style {
    public static final Color WINDOW = new Color(0x0A, 0x0C, 0x11);
    public static final Color RAIL = new Color(0x0E, 0x10, 0x16);
    public static final Color PANEL = new Color(0x13, 0x16, 0x1E);
    public static final Color RAISED = new Color(0x1A, 0x1E, 0x28);
    public static final Color HOVER = new Color(0x22, 0x27, 0x33);
    public static final Color LINE = new Color(0x24, 0x29, 0x35);

    public static final Color TEXT = new Color(0xEE, 0xF0, 0xF6);
    public static final Color MUTED = new Color(0x9A, 0xA2, 0xB4);
    public static final Color FAINT = new Color(0x62, 0x6A, 0x7C);

    public static final Color VIOLET = new Color(0x7C, 0x5C, 0xFF);
    public static final Color CYAN = new Color(0x00, 0xB2, 0xFF);
    public static final Color GREEN = new Color(0x3D, 0xDC, 0x97);
    public static final Color AMBER = new Color(0xFF, 0xB8, 0x4D);
    public static final Color RED = new Color(0xFF, 0x5C, 0x6C);

    private Style() {
    }

    public static Font font(int style, float size) {
        return Theme.ui(style, size);
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

    public static Color mix(Color from, Color to, float amount) {
        float keep = 1 - amount;
        return new Color(Math.round(from.getRed() * keep + to.getRed() * amount),
                Math.round(from.getGreen() * keep + to.getGreen() * amount),
                Math.round(from.getBlue() * keep + to.getBlue() * amount));
    }

    /** Hex for HTML text in labels. */
    public static String hex(Color colour) {
        return String.format("#%02X%02X%02X", colour.getRed(), colour.getGreen(), colour.getBlue());
    }
}
