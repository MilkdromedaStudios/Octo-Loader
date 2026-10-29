package studios.milkdromeda.octo.installer;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.List;

import javax.swing.JComponent;

/**
 * The loader's mark: an octopus, because the loader is called Octo.
 *
 * <p>Drawn rather than shipped as an image, so it is sharp at any size and costs
 * nothing to carry. The same geometry is in the loader's
 * {@code studios.milkdromeda.octo.ui.Mark}; the installer that puts Octo on the
 * disk and the window that reports on it afterwards are the same product, and
 * should look like it.
 */
final class Mark extends JComponent {
    private static final Color BODY = new Color(0x6610E8);
    private static final Color OUTER_ARM = new Color(0x9B1CFF);
    private static final Color INNER_ARM = new Color(0x00B2FF);
    private static final Color SHINE = new Color(0xAD5CFF);
    private static final Color VIOLET_GLOW = new Color(0xCF, 0x8C, 0xFF, 0x1C);
    private static final Color CYAN_GLOW = new Color(0x7F, 0xDC, 0xFF, 0x1C);

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

    /**
     * The mark itself: an octopus — a round head, a body tapering to a point,
     * two violet arms outside two cyan ones — drawn on the 1040-unit square of
     * {@code docs/logo.svg}, from which these coordinates are taken. At 32
     * pixels and up each part has a soft halo of its own colour, laid down
     * before the part so the halos read as light between the parts.
     */
    private static void paint(Graphics2D canvas, int size) {
        AffineTransform saved = canvas.getTransform();
        canvas.scale(size / 1040.0, size / 1040.0);
        canvas.translate(-107, -102);
        boolean halo = size >= 32;

        for (Shape arm : List.of(triangle(478, 627, 224, 773, 302, 864), triangle(776, 627, 1030, 773, 952, 864))) {
            part(canvas, arm, OUTER_ARM, VIOLET_GLOW, 36, halo);
        }

        for (Shape arm : List.of(triangle(546, 684, 355, 917, 457, 1032), triangle(708, 684, 899, 917, 797, 1032))) {
            part(canvas, arm, INNER_ARM, CYAN_GLOW, 36, halo);
        }

        Path2D body = new Path2D.Double();
        body.moveTo(512, 572);
        body.lineTo(486, 690);
        body.lineTo(562, 650);
        body.lineTo(550, 984);
        body.lineTo(627, 1080);
        body.lineTo(704, 984);
        body.lineTo(692, 650);
        body.lineTo(768, 690);
        body.lineTo(742, 572);
        body.closePath();
        Area whole = new Area(new Ellipse2D.Double(627 - 232, 396 - 232, 464, 464));
        whole.add(new Area(new BasicStroke(14, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(body)));
        whole.add(new Area(body));
        part(canvas, whole, BODY, VIOLET_GLOW, 0, halo);

        canvas.setColor(SHINE);
        canvas.setStroke(new BasicStroke(30, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        canvas.draw(new CubicCurve2D.Double(441, 384, 452, 304, 506, 250, 582, 224));
        canvas.setTransform(saved);
    }

    /** One part: its halo, then the part, its corners rounded by a stroke of its own colour. */
    private static void part(Graphics2D canvas, Shape shape, Color fill, Color glow, float rounding, boolean halo) {
        if (halo) {
            for (int ring = 6; ring >= 1; ring--) {
                canvas.setColor(glow);
                canvas.setStroke(new BasicStroke(rounding + ring * 8, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                canvas.draw(shape);
            }
        }

        canvas.setColor(fill);
        canvas.fill(shape);

        if (rounding > 0) {
            canvas.setStroke(new BasicStroke(rounding, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            canvas.draw(shape);
        }
    }

    private static Shape triangle(double x1, double y1, double x2, double y2, double x3, double y3) {
        Path2D path = new Path2D.Double();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.closePath();
        return path;
    }
}
