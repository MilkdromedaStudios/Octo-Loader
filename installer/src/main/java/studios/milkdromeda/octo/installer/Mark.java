package studios.milkdromeda.octo.installer;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.List;

import javax.swing.JComponent;

/**
 * The loader's mark: eight arms around a dot, because the loader is called Octo.
 *
 * <p>Drawn rather than shipped as an image, so it is sharp at any size and costs
 * nothing to carry. The same geometry is in the loader's
 * {@code studios.milkdromeda.octo.ui.Mark}; the installer that puts Octo on the
 * disk and the window that reports on it afterwards are the same product, and
 * should look like it.
 */
final class Mark extends JComponent {
    private final int size;

    Mark(int size) {
        this.size = size;
        setPreferredSize(new Dimension(size, size));
        setMinimumSize(new Dimension(size, size));
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D canvas = Theme.smooth(graphics);
        paint(canvas, size);
        canvas.dispose();
    }

    /** The window and task bar icon, at the sizes a desktop asks for. */
    static List<Image> icons() {
        return List.of(image(16), image(32), image(64), image(128));
    }

    static Image image(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D canvas = Theme.smooth(image.getGraphics());
        paint(canvas, size);
        canvas.dispose();
        return image;
    }

    private static void paint(Graphics2D canvas, int size) {
        float scale = size / 44f;

        canvas.setPaint(new GradientPaint(0, 0, Theme.ACCENT_HOVER, 0, size, Theme.ACCENT_PRESSED));
        canvas.fillRoundRect(0, 0, size, size, Math.round(14 * scale), Math.round(14 * scale));

        canvas.setColor(Theme.ON_ACCENT);
        canvas.setStroke(new BasicStroke(Math.max(1f, 2f * scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        double centre = size / 2.0;

        for (int arm = 0; arm < 8; arm++) {
            double angle = Math.PI * 2 * arm / 8;
            canvas.drawLine(
                    (int) Math.round(centre + Math.cos(angle) * 7 * scale),
                    (int) Math.round(centre + Math.sin(angle) * 7 * scale),
                    (int) Math.round(centre + Math.cos(angle) * 15 * scale),
                    (int) Math.round(centre + Math.sin(angle) * 15 * scale));
        }

        int dot = Math.max(3, Math.round(10 * scale));
        canvas.fillOval((int) Math.round(centre - dot / 2.0), (int) Math.round(centre - dot / 2.0), dot, dot);
    }
}
