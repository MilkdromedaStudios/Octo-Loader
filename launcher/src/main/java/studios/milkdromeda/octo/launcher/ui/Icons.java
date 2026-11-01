package studios.milkdromeda.octo.launcher.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

import javax.swing.Icon;

/**
 * Line icons, drawn on a 24-unit grid and scaled: crisp at any size and any
 * display scale, and in any colour, with nothing to load.
 */
public final class Icons {
    public enum Glyph {
        HOME, MODS, WORLDS, VERSIONS, SETTINGS, SEARCH, DOWNLOAD, CHECK, TRASH, CLOSE, MINIMIZE, MAXIMIZE, REFRESH,
        FOLDER, PLAY, EXTERNAL, ALERT, BACKUP, RESTORE, UPDATE, PLUS, CHEVRON
    }

    private Icons() {
    }

    public static Icon of(Glyph glyph, int size, Color colour) {
        return new Icon() {
            @Override
            public void paintIcon(Component component, Graphics graphics, int x, int y) {
                Graphics2D canvas = Style.smooth(graphics);
                canvas.translate(x, y);
                canvas.scale(size / 24.0, size / 24.0);
                canvas.setColor(colour);
                canvas.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                draw(canvas, glyph);
                canvas.dispose();
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    static void draw(Graphics2D g, Glyph glyph) {
        switch (glyph) {
            case HOME -> {
                Path2D roof = new Path2D.Double();
                roof.moveTo(3.5, 11);
                roof.lineTo(12, 4);
                roof.lineTo(20.5, 11);
                g.draw(roof);
                Path2D body = new Path2D.Double();
                body.moveTo(5.5, 9.5);
                body.lineTo(5.5, 20);
                body.lineTo(18.5, 20);
                body.lineTo(18.5, 9.5);
                g.draw(body);
                g.draw(new RoundRectangle2D.Double(10, 14, 4, 6, 1.5, 1.5));
            }
            case MODS -> {
                // A puzzle piece.
                Path2D piece = new Path2D.Double();
                piece.moveTo(5, 8);
                piece.lineTo(9, 8);
                piece.curveTo(9, 4, 15, 4, 15, 8);
                piece.lineTo(19, 8);
                piece.lineTo(19, 12);
                piece.curveTo(22.5, 12, 22.5, 17, 19, 17);
                piece.lineTo(19, 20);
                piece.lineTo(5, 20);
                piece.lineTo(5, 16);
                piece.curveTo(8, 16, 8, 11.5, 5, 11.5);
                piece.closePath();
                g.draw(piece);
            }
            case WORLDS -> {
                g.draw(new Ellipse2D.Double(3.5, 3.5, 17, 17));
                g.draw(new Ellipse2D.Double(8, 3.5, 8, 17));
                g.draw(new Line2D.Double(4, 12, 20, 12));
            }
            case VERSIONS -> {
                Path2D top = new Path2D.Double();
                top.moveTo(12, 3.5);
                top.lineTo(20.5, 8);
                top.lineTo(12, 12.5);
                top.lineTo(3.5, 8);
                top.closePath();
                g.draw(top);
                Path2D mid = new Path2D.Double();
                mid.moveTo(3.5, 12);
                mid.lineTo(12, 16.5);
                mid.lineTo(20.5, 12);
                g.draw(mid);
                Path2D low = new Path2D.Double();
                low.moveTo(3.5, 16);
                low.lineTo(12, 20.5);
                low.lineTo(20.5, 16);
                g.draw(low);
            }
            case SETTINGS -> {
                g.draw(new Ellipse2D.Double(9, 9, 6, 6));

                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI / 4 * i;
                    g.draw(new Line2D.Double(12 + Math.cos(angle) * 6.2, 12 + Math.sin(angle) * 6.2,
                            12 + Math.cos(angle) * 8.6, 12 + Math.sin(angle) * 8.6));
                }

                g.draw(new Ellipse2D.Double(5.8, 5.8, 12.4, 12.4));
            }
            case SEARCH -> {
                g.draw(new Ellipse2D.Double(4, 4, 12, 12));
                g.draw(new Line2D.Double(14.5, 14.5, 20, 20));
            }
            case DOWNLOAD -> {
                g.draw(new Line2D.Double(12, 4, 12, 15));
                Path2D head = new Path2D.Double();
                head.moveTo(7.5, 10.5);
                head.lineTo(12, 15);
                head.lineTo(16.5, 10.5);
                g.draw(head);
                g.draw(new Line2D.Double(5, 19.5, 19, 19.5));
            }
            case CHECK -> {
                Path2D tick = new Path2D.Double();
                tick.moveTo(5, 12.5);
                tick.lineTo(10, 17.5);
                tick.lineTo(19.5, 7);
                g.draw(tick);
            }
            case TRASH -> {
                g.draw(new Line2D.Double(4.5, 7, 19.5, 7));
                g.draw(new Line2D.Double(9.5, 7, 10, 4.5));
                g.draw(new Line2D.Double(14.5, 7, 14, 4.5));
                g.draw(new Line2D.Double(10, 4.5, 14, 4.5));
                Path2D bin = new Path2D.Double();
                bin.moveTo(6.5, 7);
                bin.lineTo(7.5, 20);
                bin.lineTo(16.5, 20);
                bin.lineTo(17.5, 7);
                g.draw(bin);
            }
            case CLOSE -> {
                g.draw(new Line2D.Double(6, 6, 18, 18));
                g.draw(new Line2D.Double(18, 6, 6, 18));
            }
            case MINIMIZE -> g.draw(new Line2D.Double(6, 12.5, 18, 12.5));
            case MAXIMIZE -> g.draw(new RoundRectangle2D.Double(6, 6, 12, 12, 2, 2));
            case REFRESH -> {
                g.draw(new Arc2D.Double(4.5, 4.5, 15, 15, 40, 290, Arc2D.OPEN));
                Path2D head = new Path2D.Double();
                head.moveTo(18.5, 3.5);
                head.lineTo(18.2, 8.2);
                head.lineTo(13.6, 7.6);
                g.draw(head);
            }
            case FOLDER -> {
                Path2D folder = new Path2D.Double();
                folder.moveTo(3.5, 7);
                folder.lineTo(3.5, 19);
                folder.lineTo(20.5, 19);
                folder.lineTo(20.5, 8.5);
                folder.lineTo(11.5, 8.5);
                folder.lineTo(9.5, 5.5);
                folder.lineTo(3.5, 5.5);
                folder.closePath();
                g.draw(folder);
            }
            case PLAY -> {
                Path2D play = new Path2D.Double();
                play.moveTo(7.5, 4.5);
                play.lineTo(19, 12);
                play.lineTo(7.5, 19.5);
                play.closePath();
                g.fill(play);
                g.draw(play);
            }
            case EXTERNAL -> {
                Path2D box = new Path2D.Double();
                box.moveTo(11, 5);
                box.lineTo(5, 5);
                box.lineTo(5, 19);
                box.lineTo(19, 19);
                box.lineTo(19, 13);
                g.draw(box);
                g.draw(new Line2D.Double(11, 13, 19.5, 4.5));
                Path2D head = new Path2D.Double();
                head.moveTo(14, 4.5);
                head.lineTo(19.5, 4.5);
                head.lineTo(19.5, 10);
                g.draw(head);
            }
            case ALERT -> {
                Path2D triangle = new Path2D.Double();
                triangle.moveTo(12, 3.5);
                triangle.lineTo(21, 19.5);
                triangle.lineTo(3, 19.5);
                triangle.closePath();
                g.draw(triangle);
                g.draw(new Line2D.Double(12, 9.5, 12, 13.5));
                g.fill(new Ellipse2D.Double(11, 15.6, 2, 2));
            }
            case BACKUP -> {
                g.draw(new RoundRectangle2D.Double(4, 4, 16, 16, 4, 4));
                g.draw(new Line2D.Double(12, 8, 12, 16));
                Path2D head = new Path2D.Double();
                head.moveTo(8.5, 11.5);
                head.lineTo(12, 8);
                head.lineTo(15.5, 11.5);
                g.draw(head);
            }
            case RESTORE -> {
                g.draw(new Arc2D.Double(4.5, 4.5, 15, 15, 200, -290, Arc2D.OPEN));
                Path2D head = new Path2D.Double();
                head.moveTo(3.8, 6.5);
                head.lineTo(4.6, 11);
                head.lineTo(9, 10);
                g.draw(head);
                Path2D hand = new Path2D.Double();
                hand.moveTo(12, 8.5);
                hand.lineTo(12, 12);
                hand.lineTo(14.5, 13.5);
                g.draw(hand);
            }
            case UPDATE -> {
                g.draw(new Ellipse2D.Double(3.5, 3.5, 17, 17));
                g.draw(new Line2D.Double(12, 16.5, 12, 8));
                Path2D head = new Path2D.Double();
                head.moveTo(8.5, 11);
                head.lineTo(12, 7.5);
                head.lineTo(15.5, 11);
                g.draw(head);
            }
            case CHEVRON -> {
                Path2D chevron = new Path2D.Double();
                chevron.moveTo(7, 9.5);
                chevron.lineTo(12, 14.5);
                chevron.lineTo(17, 9.5);
                g.draw(chevron);
            }
            case PLUS -> {
                g.draw(new Line2D.Double(12, 5, 12, 19));
                g.draw(new Line2D.Double(5, 12, 19, 12));
            }
        }
    }
}
