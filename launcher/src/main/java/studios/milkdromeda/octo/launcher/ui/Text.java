package studios.milkdromeda.octo.launcher.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;

/**
 * A paragraph that wraps to whatever width it is given, word by word, and
 * ends in an ellipsis when it would run past its last allowed line. Its
 * height follows its width, which the layouts in {@link Layouts} ask for.
 */
public final class Text extends JComponent {
    private static final Graphics2D MEASURE = (Graphics2D) new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            .getGraphics();

    private String text;
    private final int maxLines;
    private final int naturalWidth;

    public Text(String text, Font font, Color colour, int maxLines) {
        this(text, font, colour, maxLines, 520);
    }

    /** @param naturalWidth how wide it wants to be before it is given a width */
    public Text(String text, Font font, Color colour, int maxLines, int naturalWidth) {
        this.text = text == null ? "" : text;
        this.maxLines = maxLines <= 0 ? Integer.MAX_VALUE : maxLines;
        this.naturalWidth = naturalWidth;
        setFont(font);
        setForeground(colour);
        setOpaque(false);
    }

    public void setText(String value) {
        text = value == null ? "" : value;
        revalidate();
        repaint();
    }

    public String getText() {
        return text;
    }

    private FontMetrics metrics() {
        return MEASURE.getFontMetrics(getFont());
    }

    private int lineHeight() {
        FontMetrics metrics = metrics();
        return metrics.getAscent() + metrics.getDescent() + Math.round(getFont().getSize2D() * 0.28f);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = metrics();
        int width = getWidth() > 0 ? getWidth() : Math.min(naturalWidth, metrics.stringWidth(text) + 2);
        int lines = lines(width).size();
        return new Dimension(width, Math.max(1, lines) * lineHeight());
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** The lines the text takes at a width, the last ending in an ellipsis if it was cut. */
    List<String> lines(int width) {
        FontMetrics metrics = metrics();
        List<String> out = new ArrayList<>();

        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();

            for (String word : paragraph.split(" ")) {
                String candidate = line.length() == 0 ? word : line + " " + word;

                if (metrics.stringWidth(candidate) <= width || line.length() == 0) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }

            out.add(line.toString());
        }

        if (out.size() > maxLines) {
            List<String> cut = new ArrayList<>(out.subList(0, maxLines));
            String last = cut.get(maxLines - 1);

            while (!last.isEmpty() && metrics.stringWidth(last + "…") > width) {
                last = last.substring(0, last.length() - 1);
            }

            cut.set(maxLines - 1, last.stripTrailing() + "…");
            return cut;
        }

        // A single word wider than the line: cut it too.
        for (int i = 0; i < out.size(); i++) {
            String line = out.get(i);

            if (metrics.stringWidth(line) > width) {
                while (line.length() > 1 && metrics.stringWidth(line + "…") > width) {
                    line = line.substring(0, line.length() - 1);
                }

                out.set(i, line + "…");
            }
        }

        return out;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Style.smooth(graphics);
        g.setFont(getFont());
        g.setColor(getForeground());
        FontMetrics metrics = g.getFontMetrics();
        int y = metrics.getAscent();

        for (String line : lines(getWidth())) {
            g.drawString(line, 0, y);
            y += lineHeight();
        }

        g.dispose();
    }
}
