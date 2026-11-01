package studios.milkdromeda.octo.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;

/**
 * The game's buttons and sliders in Octo's style: dark rounded surfaces with a
 * hairline edge, lit with the accent when pointed at. Made of plain fills, so
 * a screen full of them costs what the game's own sprites do.
 */
public final class WidgetStyle {
    private WidgetStyle() {
    }

    private static int faded(int colour, AbstractWidget widget) {
        return Theme.alpha(colour, Math.round(((colour >>> 24) & 0xFF) * widget.getAlpha()));
    }

    /** A button's surface, under the label the game draws on it. */
    public static void button(GuiGraphicsExtractor g, AbstractWidget widget) {
        int x = widget.getX();
        int y = widget.getY();
        int w = widget.getWidth();
        int h = widget.getHeight();
        int r = Math.min(6, h / 3);

        if (!widget.isActive()) {
            Draw.round(g, x, y, w, h, r, faded(Theme.alpha(Theme.PANEL, 200), widget));
            Draw.outline(g, x, y, w, h, r, faded(Theme.alpha(Theme.LINE, 160), widget));
            return;
        }

        if (widget.isHoveredOrFocused()) {
            Draw.round(g, x, y, w, h, r, faded(Theme.alpha(Theme.HOVER, 235), widget));
            Draw.outline(g, x, y, w, h, r, faded(Theme.accent(), widget));
            // A thin accent line along the bottom edge.
            Draw.gradient(g, x + r, y + h - 2, w - r * 2, 1, 0, faded(Theme.accent(), widget),
                    faded(Theme.accentEnd(), widget));
        } else {
            Draw.round(g, x, y, w, h, r, faded(Theme.alpha(Theme.RAISED, 225), widget));
            Draw.outline(g, x, y, w, h, r, faded(Theme.LINE, widget));
            // A faint highlight on the top edge gives it depth.
            g.fill(x + r, y + 1, x + w - r, y + 2, faded(Theme.alpha(0xFFFFFFFF, 14), widget));
        }
    }

    /** A slider's track: dark, filled with the accent up to its value. */
    public static void sliderTrack(GuiGraphicsExtractor g, AbstractWidget widget, double value) {
        int x = widget.getX();
        int y = widget.getY();
        int w = widget.getWidth();
        int h = widget.getHeight();
        int r = Math.min(6, h / 3);
        boolean lit = widget.isHoveredOrFocused() && widget.isActive();
        Draw.round(g, x, y, w, h, r, faded(Theme.alpha(lit ? Theme.HOVER : Theme.RAISED, 225), widget));
        Draw.outline(g, x, y, w, h, r, faded(lit ? Theme.accent() : Theme.LINE, widget));
        int filled = (int) Math.round((w - 8) * Math.max(0, Math.min(1, value)));

        if (filled > 0 && widget.isActive()) {
            Draw.gradient(g, x + 4, y + h - 5, filled, 2, 1, faded(Theme.accent(), widget),
                    faded(Theme.accentEnd(), widget));
        }
    }

    /** A slider's handle: a slim rounded bar where the value is. */
    public static void sliderHandle(GuiGraphicsExtractor g, AbstractWidget widget, int handleX, int handleWidth) {
        int y = widget.getY();
        int h = widget.getHeight();
        int x = handleX + handleWidth / 2 - 2;
        int colour = widget.isActive() ? 0xFFFFFFFF : Theme.MUTED;
        Draw.round(g, x, y + 3, 4, h - 6, 2, faded(colour, widget));
    }
}
