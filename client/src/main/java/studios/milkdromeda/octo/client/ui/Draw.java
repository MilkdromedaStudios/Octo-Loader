package studios.milkdromeda.octo.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Shapes the game's GUI has no call for: rounded rectangles, outlines and
 * horizontal gradients, all made of the plain fills it has, so they cost no
 * more than the flat rectangles they replace.
 */
public final class Draw {
    private Draw() {
    }

    /** How far in each row of a corner of radius {@code r} starts. */
    private static int inset(int r, int row) {
        double dy = r - row - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
    }

    /** A filled rectangle with rounded corners. */
    public static void round(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int colour) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));

        if (r == 0) {
            g.fill(x, y, x + w, y + h, colour);
            return;
        }

        g.fill(x, y + r, x + w, y + h - r, colour);

        for (int row = 0; row < r; row++) {
            int in = inset(r, row);
            g.fill(x + in, y + row, x + w - in, y + row + 1, colour);
            g.fill(x + in, y + h - row - 1, x + w - in, y + h - row, colour);
        }
    }

    /** A one-pixel rounded outline. */
    public static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int colour) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        g.fill(x + r, y, x + w - r, y + 1, colour);
        g.fill(x + r, y + h - 1, x + w - r, y + h, colour);
        g.fill(x, y + r, x + 1, y + h - r, colour);
        g.fill(x + w - 1, y + r, x + w, y + h - r, colour);

        for (int row = 0; row < r; row++) {
            int in = inset(r, row);
            int next = row + 1 < r ? inset(r, row + 1) : 0;
            int span = Math.max(1, in - next);
            // Each corner row: from where this row starts to where the next one does.
            g.fill(x + next, y + row, x + next + span, y + row + 1, colour);
            g.fill(x + w - next - span, y + row, x + w - next, y + row + 1, colour);
            g.fill(x + next, y + h - row - 1, x + next + span, y + h - row, colour);
            g.fill(x + w - next - span, y + h - row - 1, x + w - next, y + h - row, colour);
        }
    }

    /** A rounded rectangle filled with a colour that fades left to right. */
    public static void gradient(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int from, int to) {
        int strips = Math.max(1, Math.min(16, w / 6));
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));

        for (int i = 0; i < strips; i++) {
            int x0 = x + w * i / strips;
            int x1 = x + w * (i + 1) / strips;
            int colour = Theme.mix(from, to, strips == 1 ? 0 : i / (float) (strips - 1));
            g.fill(x0, y + r, x1, y + h - r, colour);

            // The corner rows, cut to the curve.
            for (int row = 0; row < r; row++) {
                int in = inset(r, row);
                int left = Math.max(x0, x + in);
                int right = Math.min(x1, x + w - in);

                if (right > left) {
                    g.fill(left, y + row, right, y + row + 1, colour);
                    g.fill(left, y + h - row - 1, right, y + h - row, colour);
                }
            }
        }
    }

    /** A soft shadow under a panel: a few widening, fading outlines. */
    public static void shadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
        for (int i = 1; i <= 4; i++) {
            round(g, x - i, y - i + 2, w + i * 2, h + i * 2, r + i, Theme.alpha(0xFF000000, 22 - i * 4));
        }
    }
}
