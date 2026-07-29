package studios.milkdromeda.octo.installer;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.IntConsumer;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SwingConstants;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicScrollBarUI;

/** The small set of styled controls the window is built from. */
final class FlatControls {
    private FlatControls() {
    }

    static JLabel title(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.ui(Font.BOLD, 19f));
        label.setForeground(Theme.TEXT);
        return label;
    }

    static JLabel caption(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.ui(Font.PLAIN, 12f));
        label.setForeground(Theme.TEXT_MUTED);
        return label;
    }

    static JLabel fieldLabel(String text) {
        JLabel label = new JLabel(text.toUpperCase(java.util.Locale.ROOT));
        label.setFont(Theme.ui(Font.BOLD, 10.5f));
        label.setForeground(Theme.TEXT_FAINT);
        return label;
    }

    /**
     * A count or a state with a coloured dot in front of it.
     *
     * <p>Colour alone would be the whole message for anyone who cannot tell the
     * green from the red, so the text carries it too.
     */
    static JLabel chip(String text, Color colour) {
        JLabel chip = new JLabel(text) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D canvas = Theme.smooth(graphics);
                canvas.setColor(mix(colour, Theme.SURFACE, 0.82f));
                canvas.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                canvas.setColor(mix(colour, Theme.SURFACE, 0.55f));
                canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                canvas.setColor(colour);
                canvas.fillOval(11, getHeight() / 2 - 3, 6, 6);
                canvas.dispose();
                super.paintComponent(graphics);
            }
        };

        chip.setFont(Theme.ui(Font.BOLD, 11.5f));
        chip.setForeground(colour);
        chip.setBorder(BorderFactory.createEmptyBorder(6, 26, 6, 13));
        chip.setOpaque(false);
        return chip;
    }

    static JTextField field(String value) {
        JTextField field = new JTextField(value);
        field.setFont(Theme.ui(Font.PLAIN, 13f));
        field.setForeground(Theme.TEXT);
        field.setBackground(Theme.SURFACE_RAISED);
        field.setCaretColor(Theme.ACCENT);
        field.setBorder(inputBorder());
        field.setOpaque(true);
        return field;
    }

    static <T> JComboBox<T> comboBox() {
        JComboBox<T> box = new JComboBox<>();
        box.setFont(Theme.ui(Font.PLAIN, 13f));
        box.setForeground(Theme.TEXT);
        box.setBackground(Theme.SURFACE_RAISED);
        box.setBorder(inputBorder());
        box.setFocusable(true);
        box.setUI(new FlatComboUi());
        box.setRenderer(itemRenderer(box.getRenderer()));
        return box;
    }

    private static <T> ListCellRenderer<? super T> itemRenderer(ListCellRenderer<? super T> fallback) {
        return (list, value, index, selected, focused) -> {
            Component cell = fallback.getListCellRendererComponent(list, value, index, selected, focused);

            if (cell instanceof JLabel label) {
                label.setBorder(BorderFactory.createEmptyBorder(7, 11, 7, 11));
                label.setFont(Theme.ui(Font.PLAIN, 13f));
                label.setForeground(selected ? Theme.ON_ACCENT : Theme.TEXT);
                label.setBackground(selected ? Theme.ACCENT : Theme.SURFACE_RAISED);
                label.setOpaque(true);
            }

            return cell;
        };
    }

    static Border inputBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER, 1, true),
                BorderFactory.createEmptyBorder(9, 12, 9, 12));
    }

    /** A panel with the surface colour and rounded corners. */
    static JPanel card() {
        JPanel panel = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D canvas = Theme.smooth(graphics);
                canvas.setColor(Theme.SURFACE);
                canvas.fillRoundRect(0, 0, getWidth(), getHeight(), Theme.RADIUS, Theme.RADIUS);
                canvas.setColor(Theme.BORDER);
                canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Theme.RADIUS, Theme.RADIUS);
                canvas.dispose();
            }
        };

        panel.setOpaque(false);
        return panel;
    }

    /** A one-pixel rule, for separating the header and the log from the page. */
    static Component divider() {
        JPanel rule = new JPanel();
        rule.setBackground(Theme.BORDER);
        rule.setPreferredSize(new Dimension(1, 1));
        rule.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return rule;
    }

    static JPanel column() {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    static JPanel row() {
        JPanel panel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        panel.setOpaque(false);
        return panel;
    }

    /** Styles a scroll pane to match, including the bars. */
    static JScrollPane scroller(Component view) {
        JScrollPane pane = new JScrollPane(view);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.setViewportBorder(BorderFactory.createEmptyBorder());
        pane.getViewport().setBackground(Theme.SURFACE);
        pane.setBackground(Theme.SURFACE);
        pane.setOpaque(false);
        pane.getViewport().setOpaque(false);
        style(pane.getVerticalScrollBar());
        style(pane.getHorizontalScrollBar());
        return pane;
    }

    private static void style(JScrollBar bar) {
        bar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = Theme.BORDER;
                trackColor = Theme.SURFACE;
            }

            @Override
            protected javax.swing.JButton createDecreaseButton(int orientation) {
                return invisibleButton();
            }

            @Override
            protected javax.swing.JButton createIncreaseButton(int orientation) {
                return invisibleButton();
            }

            @Override
            protected void paintTrack(Graphics graphics, javax.swing.JComponent component,
                    java.awt.Rectangle bounds) {
                graphics.setColor(trackColor);
                graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }

            @Override
            protected void paintThumb(Graphics graphics, javax.swing.JComponent component,
                    java.awt.Rectangle bounds) {
                if (bounds.isEmpty() || !scrollbar.isEnabled()) {
                    return;
                }

                Graphics2D canvas = Theme.smooth(graphics);
                canvas.setColor(isThumbRollover() ? Theme.SURFACE_HOVER.brighter() : Theme.BORDER);
                canvas.fillRoundRect(bounds.x + 3, bounds.y + 3, bounds.width - 6, bounds.height - 6, 8, 8);
                canvas.dispose();
            }

            private javax.swing.JButton invisibleButton() {
                javax.swing.JButton button = new javax.swing.JButton();
                button.setPreferredSize(new Dimension(0, 0));
                button.setVisible(false);
                return button;
            }
        });

        bar.setUnitIncrement(18);
        bar.setPreferredSize(new Dimension(11, 11));
        bar.setOpaque(false);
    }

    /**
     * A two-option segmented control, in place of a tab strip.
     *
     * @param onSelect receives the index whenever the selection changes
     */
    static JPanel segmented(List<String> options, IntConsumer onSelect) {
        JPanel panel = new JPanel(new java.awt.GridLayout(1, options.size(), 4, 0)) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D canvas = (Graphics2D) graphics.create();
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(Theme.SURFACE);
                canvas.fillRoundRect(0, 0, getWidth(), getHeight(), Theme.RADIUS, Theme.RADIUS);
                canvas.dispose();
            }
        };

        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JLabel[] segments = new JLabel[options.size()];

        for (int index = 0; index < options.size(); index++) {
            int position = index;
            JLabel segment = new JLabel(options.get(index), SwingConstants.CENTER);
            segment.setFont(Theme.ui(Font.BOLD, 12.5f));
            segment.setOpaque(true);
            segment.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));
            segment.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            segment.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    select(segments, position);
                    onSelect.accept(position);
                }
            });

            segments[index] = segment;
            panel.add(segment);
        }

        select(segments, 0);
        return panel;
    }

    private static void select(JLabel[] segments, int selected) {
        for (int index = 0; index < segments.length; index++) {
            boolean active = index == selected;
            segments[index].setBackground(active ? Theme.ACCENT : Theme.SURFACE);
            segments[index].setForeground(active ? Theme.ON_ACCENT : Theme.TEXT_MUTED);
        }
    }

    /** Drops the combo box's chrome and draws a plain chevron instead. */
    private static final class FlatComboUi extends BasicComboBoxUI {
        @Override
        protected javax.swing.JButton createArrowButton() {
            javax.swing.JButton arrow = new javax.swing.JButton() {
                @Override
                protected void paintComponent(Graphics graphics) {
                    Graphics2D canvas = (Graphics2D) graphics.create();
                    canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    canvas.setColor(Theme.TEXT_MUTED);

                    int middleX = getWidth() / 2;
                    int middleY = getHeight() / 2;
                    canvas.drawLine(middleX - 4, middleY - 2, middleX, middleY + 2);
                    canvas.drawLine(middleX, middleY + 2, middleX + 4, middleY - 2);
                    canvas.dispose();
                }
            };

            arrow.setBorder(BorderFactory.createEmptyBorder());
            arrow.setContentAreaFilled(false);
            arrow.setFocusPainted(false);
            return arrow;
        }

        @Override
        public void paintCurrentValueBackground(Graphics graphics, java.awt.Rectangle bounds, boolean hasFocus) {
            graphics.setColor(Theme.SURFACE_RAISED);
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected javax.swing.plaf.basic.ComboPopup createPopup() {
            javax.swing.plaf.basic.BasicComboPopup popup =
                    (javax.swing.plaf.basic.BasicComboPopup) super.createPopup();
            popup.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
            popup.getList().setBackground(Theme.SURFACE_RAISED);
            popup.getList().setSelectionBackground(Theme.ACCENT);
            return popup;
        }
    }

    /** Left-aligns and colours a plain list, used by the version picker's popup. */
    static void styleList(JList<?> list) {
        DefaultListCellRenderer renderer = new DefaultListCellRenderer();
        renderer.setHorizontalAlignment(SwingConstants.LEFT);
        list.setBackground(Theme.SURFACE_RAISED);
        list.setForeground(Theme.TEXT);
        list.setSelectionBackground(Theme.ACCENT);
        list.setSelectionForeground(Theme.ON_ACCENT);
    }

    static Color mix(Color from, Color to, float amount) {
        return new Color(
                Math.round(from.getRed() + (to.getRed() - from.getRed()) * amount),
                Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * amount),
                Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * amount));
    }
}
