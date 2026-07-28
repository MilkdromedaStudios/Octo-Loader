package studios.milkdromeda.octo.ui;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.Image;
import java.awt.image.BufferedImage;

import javax.swing.JComponent;

/**
 * The loader's mark: eight arms around a dot, because the loader is called Octo.
 *
 * <p>Drawn rather than shipped as an image. The loader jar goes on the class
 * path of every launch, the mark is needed on exactly the launches that open a
 * window, and vector arithmetic is cheaper to carry around than four sizes of
 * PNG — as well as being sharp on displays that did not exist when it was
 * written.
 */
public final class Mark extends JComponent {
    private final int size;

    public Mark(int size) {
        this.size = size;
        setPreferredSize(new Dimension(size, size));
        setMinimumSize(new Dimension(size, size));
        setOpaque(false);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D canvas = Skin.smooth(graphics);
        paint(canvas, size);
        canvas.dispose();
    }

    /** The same mark on its own bitmap, for the window and task bar icon. */
    public static Image image(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D canvas = Skin.smooth(image.getGraphics());
        paint(canvas, size);
        canvas.dispose();
        return image;
    }

    private static void paint(Graphics2D canvas, int size) {
        float scale = size / 44f;
        int radius = Math.round(14 * scale);

        // A gentle top-to-bottom gradient rather than a flat fill: at 44px it
        // reads as a lit tile instead of a coloured square.
        canvas.setPaint(new GradientPaint(0, 0, Skin.ACCENT_HOVER, 0, size, Skin.ACCENT_PRESSED));
        canvas.fillRoundRect(0, 0, size, size, radius, radius);

        canvas.setColor(Skin.ON_ACCENT);
        canvas.setStroke(new BasicStroke(Math.max(1f, 2f * scale), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        double centre = size / 2.0;
        double inner = 7 * scale;
        double outer = 15 * scale;

        for (int arm = 0; arm < 8; arm++) {
            double angle = Math.PI * 2 * arm / 8;
            canvas.drawLine(
                    (int) Math.round(centre + Math.cos(angle) * inner),
                    (int) Math.round(centre + Math.sin(angle) * inner),
                    (int) Math.round(centre + Math.cos(angle) * outer),
                    (int) Math.round(centre + Math.sin(angle) * outer));
        }

        int dot = Math.max(3, Math.round(10 * scale));
        canvas.fillOval((int) Math.round(centre - dot / 2.0), (int) Math.round(centre - dot / 2.0), dot, dot);
    }
}
