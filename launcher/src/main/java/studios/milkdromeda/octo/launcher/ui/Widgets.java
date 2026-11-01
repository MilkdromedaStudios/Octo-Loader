package studios.milkdromeda.octo.launcher.ui;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** The client's building blocks: cards, buttons, pills, switches, fields and scroll panes, all drawn. */
public final class Widgets {
    private Widgets() {
    }

    // -------------------------------------------------------------- panels

    /** A rounded panel in a colour, with an optional hairline border. */
    public static class Card extends JPanel {
        private final Color fill;
        private final int radius;
        private final boolean border;
        private boolean hover;
        private boolean hoverable;

        public Card(Color fill, int radius, boolean border) {
            super(new BorderLayout());
            this.fill = fill;
            this.radius = radius;
            this.border = border;
            setOpaque(false);
        }

        /** Lightens a little under the mouse, for a card that can be clicked. */
        public Card hoverable(Runnable onClick) {
            hoverable = true;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    if (!contains(event.getPoint())) {
                        hover = false;
                        repaint();
                    }
                }

                @Override
                public void mouseClicked(MouseEvent event) {
                    if (onClick != null) {
                        onClick.run();
                    }
                }
            });
            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Style.smooth(graphics);
            g.setColor(hoverable && hover ? Style.mix(fill, Color.WHITE, 0.04f) : fill);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);

            if (border) {
                g.setColor(hoverable && hover ? Style.mix(Style.LINE, Style.VIOLET, 0.35f) : Style.LINE);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            }

            g.dispose();
        }
    }

    public static Card card() {
        Card card = new Card(Style.PANEL, 16, true);
        card.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
        return card;
    }

    public static JPanel clear(java.awt.LayoutManager layout) {
        JPanel panel = new JPanel(layout);
        panel.setOpaque(false);
        return panel;
    }

    /** A column whose rows each get the full width and the height they need at it. */
    public static JPanel stack(int gap) {
        return clear(new Layouts.Stack(gap));
    }

    /** Cards in as many columns as fit. */
    public static JPanel grid(int minColumn, int maxColumns, int gap) {
        return clear(new Layouts.Grid(minColumn, maxColumns, gap));
    }

    /** Left to right, wrapping. */
    public static JPanel flow(int hgap, int vgap) {
        return clear(new Layouts.Flow(hgap, vgap));
    }

    /** A paragraph that wraps to its width. */
    public static Text paragraph(String value, float size, Color colour, int maxLines) {
        return new Text(value, Style.font(Font.PLAIN, size), colour, maxLines);
    }

    /** A single line that ends in an ellipsis when it does not fit. */
    public static Text line(String value, Font font, Color colour) {
        return new Text(value, font, colour, 1, 1000);
    }

    public static JPanel row(int gap) {
        return clear(new FlowLayout(FlowLayout.LEFT, gap, 0));
    }

    public static JPanel column() {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    // --------------------------------------------------------------- text

    public static JLabel text(String value, float size, int style, Color colour) {
        JLabel label = new JLabel(value);
        label.setFont(Style.font(style, size));
        label.setForeground(colour);
        return label;
    }

    public static JLabel heading(String value) {
        JLabel label = new JLabel(value);
        label.setFont(Style.display(24f));
        label.setForeground(Style.TEXT);
        return label;
    }

    public static JLabel section(String value) {
        JLabel label = text(value.toUpperCase(java.util.Locale.ROOT), 11.5f, Font.BOLD, Style.FAINT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 2, 8, 0));
        return label;
    }

    /** HTML text wrapped to a width. */
    public static JLabel wrapped(String html, float size, Color colour, int width) {
        return text("<html><div style='width:" + width + "px'>" + html + "</div></html>", size, Font.PLAIN, colour);
    }

    // ------------------------------------------------------------ buttons

    public enum Kind {
        /** The accent gradient: the one thing to do. */
        PRIMARY,
        /** A raised surface. */
        SECONDARY,
        /** Text and an icon, no surface until hovered. */
        GHOST,
        /** Red text: removes something. */
        DANGER,
        /** Filled red: the button that deletes, at the end of asking. */
        DESTRUCTIVE
    }

    /** A rounded button that lightens when hovered and dims when it cannot be pressed. */
    public static class Button extends JButton {
        private final Kind kind;
        private boolean hover;
        private boolean pressed;
        private final int radius;

        public Button(String text, Icon icon, Kind kind) {
            this(text, icon, kind, 10);
        }

        public Button(String text, Icon icon, Kind kind, int radius) {
            super(text, icon);
            this.kind = kind;
            this.radius = radius;
            setFont(Style.font(Font.BOLD, 13f));
            setForeground(kind == Kind.PRIMARY || kind == Kind.DESTRUCTIVE ? Color.WHITE
                    : kind == Kind.DANGER ? Style.RED : Style.TEXT);
            setHorizontalTextPosition(javax.swing.SwingConstants.RIGHT);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setIconTextGap(8);
            setBorder(BorderFactory.createEmptyBorder(9, 16, 9, 16));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover = false;
                    pressed = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent event) {
                    pressed = true;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent event) {
                    pressed = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Style.smooth(graphics);
            int w = getWidth();
            int h = getHeight();

            if (!isEnabled()) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
            }

            switch (kind) {
                case PRIMARY -> {
                    if (hover && isEnabled()) {
                        g.setColor(Style.alpha(Style.VIOLET, 60));
                        g.fillRoundRect(-2, 1, w + 4, h + 2, radius + 6, radius + 6);
                    }

                    g.setPaint(Style.accent(0, w));
                    g.fillRoundRect(0, 0, w, h, radius, radius);

                    if (hover || pressed) {
                        g.setColor(pressed ? new Color(0, 0, 0, 40) : new Color(255, 255, 255, 26));
                        g.fillRoundRect(0, 0, w, h, radius, radius);
                    }
                }
                case SECONDARY -> {
                    g.setColor(pressed ? Style.RAISED : hover ? Style.HOVER : Style.RAISED);
                    g.fillRoundRect(0, 0, w, h, radius, radius);
                    g.setColor(Style.LINE);
                    g.drawRoundRect(0, 0, w - 1, h - 1, radius, radius);
                }
                case DESTRUCTIVE -> {
                    g.setColor(pressed ? Style.mix(Style.RED, Color.BLACK, 0.2f)
                            : hover ? Style.mix(Style.RED, Color.WHITE, 0.08f) : Style.mix(Style.RED, Color.BLACK, 0.06f));
                    g.fillRoundRect(0, 0, w, h, radius, radius);
                }
                case GHOST, DANGER -> {
                    if (hover) {
                        g.setColor(kind == Kind.DANGER ? Style.alpha(Style.RED, 28) : Style.HOVER);
                        g.fillRoundRect(0, 0, w, h, radius, radius);
                    }
                }
            }

            g.dispose();
            super.paintComponent(graphics);
        }
    }

    /** A button that opens a menu: its text, then a chevron. */
    public static Button dropdown(String text) {
        Button button = new Button(text, Icons.of(Icons.Glyph.CHEVRON, 14, Style.MUTED), Kind.SECONDARY);
        button.setHorizontalTextPosition(javax.swing.SwingConstants.LEFT);
        return button;
    }

    public static Button button(String text, Icons.Glyph glyph, Kind kind) {
        Color colour = kind == Kind.PRIMARY || kind == Kind.DESTRUCTIVE ? Color.WHITE
                : kind == Kind.DANGER ? Style.RED : Style.TEXT;
        return new Button(text, glyph == null ? null : Icons.of(glyph, 16, colour), kind);
    }

    /** A square button holding only an icon, with a tooltip. */
    public static Button iconButton(Icons.Glyph glyph, String tip, Kind kind) {
        Color colour = kind == Kind.DANGER ? Style.RED : Style.MUTED;
        Button button = new Button(null, Icons.of(glyph, 17, colour), kind, 9);
        button.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        button.setToolTipText(tip);
        return button;
    }

    // -------------------------------------------------------------- pills

    /** A small rounded label: a loader, a state, a count. */
    public static JLabel pill(String text, Color colour) {
        JLabel label = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Style.smooth(graphics);
                g.setColor(Style.alpha(colour, 34));
                g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        label.setFont(Style.font(Font.BOLD, 11f));
        label.setForeground(colour);
        label.setBorder(BorderFactory.createEmptyBorder(3, 9, 3, 9));
        return label;
    }

    // ------------------------------------------------------------- switch

    /** An on/off switch that slides. */
    public static class Switch extends JComponent {
        private boolean on;
        private float position;
        private final Timer slide;
        private Consumer<Boolean> listener;

        public Switch(boolean on) {
            this.on = on;
            this.position = on ? 1 : 0;
            setPreferredSize(new Dimension(40, 22));
            setMaximumSize(new Dimension(40, 22));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            slide = new Timer(12, event -> {
                float target = this.on ? 1 : 0;
                position += (target - position) * 0.35f;

                if (Math.abs(target - position) < 0.02f) {
                    position = target;
                    ((Timer) event.getSource()).stop();
                }

                repaint();
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (isEnabled()) {
                        set(!Switch.this.on, true);
                    }
                }
            });
        }

        public void onChange(Consumer<Boolean> value) {
            this.listener = value;
        }

        public boolean isOn() {
            return on;
        }

        public void set(boolean value, boolean notify) {
            on = value;
            slide.start();

            if (notify && listener != null) {
                listener.accept(value);
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Style.smooth(graphics);
            int w = 40;
            int h = 22;
            g.setColor(Style.mix(Style.HOVER, Style.VIOLET, position));
            g.fillRoundRect(0, 0, w, h, h, h);
            g.setColor(Color.WHITE);
            int knob = h - 6;
            g.fillOval(3 + Math.round((w - knob - 6) * position), 3, knob, knob);
            g.dispose();
        }
    }

    // -------------------------------------------------------------- field

    /** A rounded text field with an icon and a hint while it is empty. */
    public static class Field extends JTextField {
        private final String hint;
        private final Icon icon;
        private boolean focused;

        public Field(String hint, Icons.Glyph glyph) {
            this.hint = hint;
            this.icon = glyph == null ? null : Icons.of(glyph, 16, Style.FAINT);
            setOpaque(false);
            setForeground(Style.TEXT);
            setCaretColor(Style.TEXT);
            setFont(Style.font(Font.PLAIN, 13.5f));
            setBorder(BorderFactory.createEmptyBorder(9, icon == null ? 14 : 38, 9, 14));
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent event) {
                    focused = true;
                    repaint();
                }

                @Override
                public void focusLost(FocusEvent event) {
                    focused = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Style.smooth(graphics);
            g.setColor(Style.RAISED);
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
            g.setColor(focused ? Style.VIOLET : Style.LINE);
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);

            if (icon != null) {
                icon.paintIcon(this, g, 13, (getHeight() - 16) / 2);
            }

            if (getText().isEmpty() && hint != null) {
                g.setFont(getFont());
                g.setColor(Style.FAINT);
                Insets insets = getInsets();
                FontMetrics metrics = g.getFontMetrics();
                g.drawString(hint, insets.left, (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2);
            }

            g.dispose();
            super.paintComponent(graphics);
        }
    }

    // ------------------------------------------------------------- scroll

    /**
     * A scroll pane for a page: the content is as wide as the view and only
     * scrolls up and down, so nothing is cut off at the right.
     */
    public static JScrollPane page(Component content) {
        Tracking holder = new Tracking();
        holder.add(content, BorderLayout.NORTH);
        return scroll(holder);
    }

    /** A panel that takes the width of the view it scrolls in. */
    public static final class Tracking extends JPanel implements javax.swing.Scrollable {
        public Tracking() {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 18;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(40, visible.height - 60);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /** A scroll pane with a thin rounded bar and no frame. */
    public static JScrollPane scroll(Component view) {
        JScrollPane pane = new JScrollPane(view);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.setViewportBorder(null);
        pane.setOpaque(false);
        pane.getViewport().setOpaque(false);
        pane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        pane.getVerticalScrollBar().setUnitIncrement(18);
        pane.getVerticalScrollBar().setUI(new ThinBar());
        pane.getVerticalScrollBar().setOpaque(false);
        pane.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        return pane;
    }

    private static final class ThinBar extends BasicScrollBarUI {
        @Override
        protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds) {
        }

        @Override
        protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
            if (bounds.isEmpty()) {
                return;
            }

            Graphics2D g = Style.smooth(graphics);
            g.setColor(isThumbRollover() ? Style.FAINT : Style.LINE);
            g.fillRoundRect(bounds.x + 2, bounds.y + 2, bounds.width - 4, bounds.height - 4, 6, 6);
            g.dispose();
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zero();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zero();
        }

        private static JButton zero() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            return button;
        }

        @Override
        protected void configureScrollBarColors() {
            thumbColor = Style.LINE;
            trackColor = Style.WINDOW;
        }
    }

    // ------------------------------------------------------------- images

    /** An image scaled into a rounded square. */
    public static Image rounded(Image source, int size, int radius) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Style.smooth(out.getGraphics());
        g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, size, size, radius, radius));
        g.drawImage(source, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    /** A rounded tile in a colour with a name's initials: an icon for a mod or world that has none. */
    public static Image monogram(String name, Color colour, int size, int radius) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = Style.smooth(out.getGraphics());
        Color base = colour == null ? Style.VIOLET : colour;
        g.setPaint(new GradientPaint(0, 0, Style.mix(base, Color.WHITE, 0.12f), size, size,
                Style.mix(base, Color.BLACK, 0.35f)));
        g.fillRoundRect(0, 0, size, size, radius, radius);
        String initials = initials(name);
        g.setFont(Style.font(Font.BOLD, size * 0.38f));
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(new Color(255, 255, 255, 235));
        g.drawString(initials, (size - metrics.stringWidth(initials)) / 2,
                (size + metrics.getAscent() - metrics.getDescent()) / 2);
        g.dispose();
        return out;
    }

    static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "?";
        }

        String[] words = name.replaceAll("[^A-Za-z0-9 ]", " ").strip().split("\\s+");

        if (words.length == 0 || words[0].isEmpty()) {
            return name.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        }

        return (words[0].substring(0, 1) + (words.length > 1 ? words[1].substring(0, 1) : ""))
                .toUpperCase(java.util.Locale.ROOT);
    }

    /** A colour of its own for a name, from the accent family. */
    public static Color colourFor(String name) {
        Color[] family = { Style.VIOLET, Style.CYAN, new Color(0xE0, 0x5C, 0xFF), new Color(0x3D, 0xB8, 0x8F),
                new Color(0xFF, 0x8A, 0x4C), new Color(0x4C, 0x7D, 0xFF) };
        return family[Math.floorMod(name == null ? 0 : name.hashCode(), family.length)];
    }

    /** A label showing an image. */
    public static JLabel picture(Image image) {
        return new JLabel(new javax.swing.ImageIcon(image));
    }

    /** Formats a download count: 28.6M, 412K, 950. */
    public static String count(long value) {
        if (value >= 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1fM", value / 1_000_000.0);
        }

        if (value >= 1_000) {
            return String.format(java.util.Locale.ROOT, "%.0fK", value / 1_000.0);
        }

        return Long.toString(value);
    }

    /** A stroke for outlines. */
    public static BasicStroke hairline() {
        return new BasicStroke(1f);
    }
}
