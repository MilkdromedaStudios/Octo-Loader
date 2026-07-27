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
        label.setFont(Theme.ui(Font.BOLD, 20f));
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
        label.setForeground(Theme.TEXT_MUTED);
        return label;
    }

    static JTextField field(String value) {
        JTextField field = new JTextField(value);
        field.setFont(Theme.ui(Font.PLAIN, 13f));
        field.setForeground(Theme.TEXT);
        field.setBackground(Theme.SURFACE);
        field.setCaretColor(Theme.ACCENT);
        field.setBorder(inputBorder());
        field.setOpaque(true);
        return field;
    }

    static <T> JComboBox<T> comboBox() {
        JComboBox<T> box = new JComboBox<>();
        box.setFont(Theme.ui(Font.PLAIN, 13f));
        box.setForeground(Theme.TEXT);
        box.setBackground(Theme.SURFACE);
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
                label.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
                label.setFont(Theme.ui(Font.PLAIN, 13f));
                label.setForeground(selected ? Theme.ON_ACCENT : Theme.TEXT);
                label.setBackground(selected ? Theme.ACCENT : Theme.SURFACE);
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
                Graphics2D canvas = (Graphics2D) graphics.create();
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(Theme.SURFACE);
                canvas.fillRoundRect(0, 0, getWidth(), getHeight(), Theme.RADIUS + 4, Theme.RADIUS + 4);
                canvas.setColor(Theme.BORDER);
                canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Theme.RADIUS + 4, Theme.RADIUS + 4);
                canvas.dispose();
            }
        };

        panel.setOpaque(false);
        return panel;
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
        pane.setBorder(BorderFactory.createLineBorder(Theme.BORDER, 1, true));
        pane.getViewport().setBackground(Theme.BACKGROUND);
        pane.setBackground(Theme.BACKGROUND);
        style(pane.getVerticalScrollBar());
        style(pane.getHorizontalScrollBar());
        return pane;
    }

    private static void style(JScrollBar bar) {
        bar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = Theme.BORDER;
                trackColor = Theme.BACKGROUND;
            }

            @Override
            protected javax.swing.JButton createDecreaseButton(int orientation) {
                return invisibleButton();
            }

            @Override
            protected javax.swing.JButton createIncreaseButton(int orientation) {
                return invisibleButton();
            }

            private javax.swing.JButton invisibleButton() {
                javax.swing.JButton button = new javax.swing.JButton();
                button.setPreferredSize(new Dimension(0, 0));
                button.setVisible(false);
                return button;
            }
        });

        bar.setUnitIncrement(16);
        bar.setBackground(Theme.BACKGROUND);
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
            graphics.setColor(Theme.SURFACE);
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected javax.swing.plaf.basic.ComboPopup createPopup() {
            javax.swing.plaf.basic.BasicComboPopup popup =
                    (javax.swing.plaf.basic.BasicComboPopup) super.createPopup();
            popup.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
            popup.getList().setBackground(Theme.SURFACE);
            popup.getList().setSelectionBackground(Theme.ACCENT);
            return popup;
        }
    }

    /** Left-aligns and colours a plain list, used by the version picker's popup. */
    static void styleList(JList<?> list) {
        DefaultListCellRenderer renderer = new DefaultListCellRenderer();
        renderer.setHorizontalAlignment(SwingConstants.LEFT);
        list.setBackground(Theme.SURFACE);
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
