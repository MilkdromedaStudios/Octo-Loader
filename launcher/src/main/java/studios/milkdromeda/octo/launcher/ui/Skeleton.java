package studios.milkdromeda.octo.launcher.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * A card-shaped placeholder with a light passing over it, shown where
 * something is still loading so the page has its shape from the start.
 */
public final class Skeleton extends JComponent {
    /** The bar colour, from the theme in use when it paints. */
    private static Color base() {
        return Style.mix(Style.PANEL, Style.RAISED, 0.5f);
    }
    private final Timer shimmer;
    private final int height;
    private final boolean withIcon;
    private float phase;

    public Skeleton(int height, boolean withIcon) {
        this.height = height;
        this.withIcon = withIcon;
        setOpaque(false);
        shimmer = new Timer(40, event -> {
            phase = (phase + 0.025f) % 1.6f;
            repaint();
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        shimmer.start();
    }

    @Override
    public void removeNotify() {
        shimmer.stop();
        super.removeNotify();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(200, height);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Style.smooth(graphics);
        int w = getWidth();
        int h = getHeight();
        g.setColor(Style.PANEL);
        g.fillRoundRect(0, 0, w, h, 16, 16);
        g.setColor(Style.LINE);
        g.drawRoundRect(0, 0, w - 1, h - 1, 16, 16);
        int x = 16;

        if (withIcon) {
            g.setColor(base());
            g.fillRoundRect(16, 16, 56, 56, 14, 14);
            x = 88;
        }

        g.setColor(base());
        g.fillRoundRect(x, 20, Math.max(40, (w - x) / 2), 12, 6, 6);
        g.fillRoundRect(x, 44, Math.max(40, w - x - 24), 9, 5, 5);
        g.fillRoundRect(x, 60, Math.max(40, (w - x) * 2 / 3), 9, 5, 5);

        float sweep = (phase - 0.3f) * w;
        g.setPaint(new GradientPaint(sweep, 0, new Color(255, 255, 255, 0), sweep + w * 0.15f, 0,
                new Color(255, 255, 255, 14), true));
        g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h, 16, 16));
        g.fillRect(0, 0, w, h);
        g.dispose();
    }
}
