package studios.milkdromeda.octo.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.RenderingHints;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * The palette, type and painted parts the loader's windows are built from.
 *
 * <p>Hand-rolled rather than pulled from a theming library for the same reason
 * the installer's copy is: this code runs inside the player's game process,
 * before any mod exists, and the loader jar is on the class path of every launch.
 * A megabyte of look-and-feel to get rounded corners would be paid for on every
 * start-up by everyone, including the launches that never open a window.
 *
 * <p>The installer keeps its own copy of this in
 * {@code studios.milkdromeda.octo.installer.Theme}. That is deliberate: the two
 * jars ship separately and neither can depend on the other without swallowing it
 * whole, so the design is shared by keeping the numbers the same rather than by
 * sharing a class. The palettes below are the source of truth for both.
 */
public final class Skin {
    /** Window background: near-black, faintly blue, so white text is not glaring. */
    public static final Color BACKGROUND = new Color(0x0F, 0x11, 0x16);
    /** Panels and cards sitting on the background. */
    public static final Color SURFACE = new Color(0x17, 0x1A, 0x21);
    /** Rows, inputs and anything raised off a card. */
    public static final Color SURFACE_RAISED = new Color(0x1E, 0x22, 0x2B);
    public static final Color SURFACE_HOVER = new Color(0x26, 0x2B, 0x36);
    public static final Color BORDER = new Color(0x2A, 0x2F, 0x3A);

    public static final Color TEXT = new Color(0xE9, 0xEC, 0xF2);
    public static final Color TEXT_MUTED = new Color(0x98, 0xA0, 0xB0);
    public static final Color TEXT_FAINT = new Color(0x6C, 0x74, 0x84);

    /** The one saturated colour: primary actions, focus rings, selection. */
    public static final Color ACCENT = new Color(0x7C, 0x6B, 0xF5);
    public static final Color ACCENT_HOVER = new Color(0x8E, 0x7E, 0xF7);
    public static final Color ACCENT_PRESSED = new Color(0x6A, 0x59, 0xE0);
    public static final Color ON_ACCENT = new Color(0x0E, 0x0F, 0x14);

    /** A mod the player installed is not running. */
    public static final Color ERROR = new Color(0xFF, 0x6B, 0x6B);
    /** A mod is running, but not as its author built it. */
    public static final Color WARNING = new Color(0xF5, 0xB4, 0x4A);
    public static final Color SUCCESS = new Color(0x4A, 0xDE, 0x9B);

    public static final int RADIUS = 12;

    private Skin() {
    }

    // -------------------------------------------------------------------- type

    public static Font ui(int style, float size) {
        return new Font(family(UI_FACES, Font.SANS_SERIF), Font.PLAIN, 13).deriveFont(style, size);
    }

    public static Font mono(float size) {
        return new Font(family(MONO_FACES, Font.MONOSPACED), Font.PLAIN, 12).deriveFont(size);
    }

    /** The platform's own UI face first, so the window looks of its decade. */
    private static final List<String> UI_FACES = List.of("Segoe UI Variable Text", "Segoe UI", "SF Pro Text",
            "Inter", "Ubuntu", "Noto Sans", "DejaVu Sans");

    private static final List<String> MONO_FACES = List.of("JetBrains Mono", "Cascadia Mono", "SF Mono",
            "Consolas", "Menlo", "DejaVu Sans Mono");

    private static String family(List<String> candidates, String fallback) {
        try {
            List<String> available = List.of(GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames());

            for (String candidate : candidates) {
                if (available.contains(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // A font list that cannot be read is not a reason to fail to draw.
        }

        return fallback;
    }

    // ------------------------------------------------------------------ labels

    public static JLabel title(String text) {
        return label(text, ui(Font.BOLD, 19f), TEXT);
    }

    public static JLabel heading(String text) {
        return label(text, ui(Font.BOLD, 14f), TEXT);
    }

    public static JLabel body(String text) {
        return label(text, ui(Font.PLAIN, 13f), TEXT);
    }

    public static JLabel caption(String text) {
        return label(text, ui(Font.PLAIN, 12f), TEXT_MUTED);
    }

    /** The small upper-case label that sits above a field or a list. */
    public static JLabel overline(String text) {
        JLabel label = label(text.toUpperCase(java.util.Locale.ROOT), ui(Font.BOLD, 10.5f), TEXT_FAINT);
        label.putClientProperty("octo.overline", Boolean.TRUE);
        return label;
    }

    private static JLabel label(String text, Font font, Color colour) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(colour);
        return label;
    }

    // ----------------------------------------------------------------- surfaces

    /** A rounded panel in the surface colour, with a hairline edge. */
    public static JPanel card() {
        return card(SURFACE);
    }

    public static JPanel card(Color fill) {
        JPanel panel = new JPanel(new java.awt.BorderLayout()) {
            @Override
            protected void paintComponent(java.awt.Graphics graphics) {
                Graphics2D canvas = smooth(graphics);
                canvas.setColor(fill);
                canvas.fillRoundRect(0, 0, getWidth(), getHeight(), RADIUS, RADIUS);
                canvas.setColor(BORDER);
                canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, RADIUS, RADIUS);
                canvas.dispose();
            }
        };

        panel.setOpaque(false);
        return panel;
    }

    public static JPanel column() {
        JPanel panel = new JPanel();
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        return panel;
    }

    public static JPanel row(int gap) {
        JPanel panel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, gap, 0));
        panel.setOpaque(false);
        return panel;
    }

    /** A one-pixel rule, used between the header, the body and the actions. */
    public static Component divider() {
        JPanel rule = new JPanel();
        rule.setBackground(BORDER);
        rule.setPreferredSize(new Dimension(1, 1));
        rule.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return rule;
    }

    /**
     * A count with a coloured dot: "3 not running", "1 adjusted".
     *
     * <p>Colour alone would be the whole message for anyone who cannot see the
     * difference between the red and the amber, so the text says it too.
     */
    public static JLabel chip(String text, Color colour) {
        JLabel chip = new JLabel(text) {
            @Override
            protected void paintComponent(java.awt.Graphics graphics) {
                Graphics2D canvas = smooth(graphics);
                canvas.setColor(mix(colour, SURFACE, 0.82f));
                canvas.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                canvas.setColor(mix(colour, SURFACE, 0.55f));
                canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                canvas.setColor(colour);
                canvas.fillOval(11, getHeight() / 2 - 3, 6, 6);
                canvas.dispose();
                super.paintComponent(graphics);
            }
        };

        chip.setFont(ui(Font.BOLD, 11.5f));
        chip.setForeground(colour);
        chip.setBorder(BorderFactory.createEmptyBorder(6, 26, 6, 13));
        chip.setOpaque(false);
        return chip;
    }

    /**
     * A text field that says what it is for while it is empty.
     *
     * <p>The placeholder is painted rather than set as the text, so an empty
     * field is genuinely empty to everything that reads it.
     */
    public static javax.swing.JTextField searchField(String placeholder) {
        javax.swing.JTextField field = new javax.swing.JTextField() {
            @Override
            protected void paintComponent(java.awt.Graphics graphics) {
                super.paintComponent(graphics);

                if (!getText().isEmpty()) {
                    return;
                }

                Graphics2D canvas = smooth(graphics);
                canvas.setColor(TEXT_FAINT);
                canvas.setFont(getFont());
                java.awt.Insets insets = getInsets();
                canvas.drawString(placeholder, insets.left,
                        getHeight() / 2 + canvas.getFontMetrics().getAscent() / 2 - 1);
                canvas.dispose();
            }
        };

        field.setFont(ui(Font.PLAIN, 12.5f));
        field.setForeground(TEXT);
        field.setBackground(SURFACE_RAISED);
        field.setCaretColor(ACCENT);
        field.setBorder(inputBorder());
        field.setOpaque(true);
        return field;
    }

    public static Border inputBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1, true),
                BorderFactory.createEmptyBorder(8, 11, 8, 11));
    }

    public static Border padding(int top, int left, int bottom, int right) {
        return BorderFactory.createEmptyBorder(top, left, bottom, right);
    }

    // ----------------------------------------------------------------- scrolling

    /** A scroll pane with no chrome and a thin, quiet bar. */
    public static JScrollPane scroller(Component view) {
        JScrollPane pane = new JScrollPane(view);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.setViewportBorder(BorderFactory.createEmptyBorder());
        pane.getViewport().setBackground(SURFACE);
        pane.setBackground(SURFACE);
        pane.setOpaque(false);
        pane.getViewport().setOpaque(false);
        styleBar(pane.getVerticalScrollBar());
        styleBar(pane.getHorizontalScrollBar());
        return pane;
    }

    public static void styleBar(JScrollBar bar) {
        bar.setUI(new BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = BORDER;
                trackColor = SURFACE;
            }

            @Override
            protected JButton createDecreaseButton(int orientation) {
                return zeroButton();
            }

            @Override
            protected JButton createIncreaseButton(int orientation) {
                return zeroButton();
            }

            @Override
            protected void paintTrack(java.awt.Graphics graphics, javax.swing.JComponent component,
                    java.awt.Rectangle bounds) {
                graphics.setColor(trackColor);
                graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
            }

            @Override
            protected void paintThumb(java.awt.Graphics graphics, javax.swing.JComponent component,
                    java.awt.Rectangle bounds) {
                if (bounds.isEmpty() || !scrollbar.isEnabled()) {
                    return;
                }

                Graphics2D canvas = smooth(graphics);
                canvas.setColor(isThumbRollover() ? SURFACE_HOVER.brighter() : BORDER);
                canvas.fillRoundRect(bounds.x + 3, bounds.y + 3, bounds.width - 6, bounds.height - 6, 8, 8);
                canvas.dispose();
            }

            private JButton zeroButton() {
                JButton button = new JButton();
                button.setPreferredSize(new Dimension(0, 0));
                button.setVisible(false);
                return button;
            }
        });

        bar.setUnitIncrement(18);
        bar.setPreferredSize(new Dimension(11, 11));
        bar.setOpaque(false);
    }

    // -------------------------------------------------------------------- paint

    public static Graphics2D smooth(java.awt.Graphics graphics) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        canvas.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return canvas;
    }

    public static Color mix(Color from, Color to, float amount) {
        float ratio = Math.max(0f, Math.min(1f, amount));
        return new Color(
                Math.round(from.getRed() + (to.getRed() - from.getRed()) * ratio),
                Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * ratio),
                Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * ratio));
    }

    /** Window icons, drawn rather than shipped, at the sizes a task bar asks for. */
    public static List<Image> icons() {
        return List.of(Mark.image(16), Mark.image(32), Mark.image(64), Mark.image(128));
    }

    /**
     * Teaches the shared dialogs — the file chooser, the message box — the same
     * colours, so one does not arrive as a white rectangle in a dark window.
     */
    public static void applyToDialogs() {
        Font font = ui(Font.PLAIN, 13f);

        for (String key : List.of("Panel", "OptionPane", "Label", "Button", "TextField", "TextArea", "List",
                "ScrollPane", "Viewport", "ToolTip", "ComboBox")) {
            UIManager.put(key + ".background", SURFACE);
            UIManager.put(key + ".foreground", TEXT);
            UIManager.put(key + ".font", font);
        }

        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("ToolTip.background", SURFACE_HOVER);
        UIManager.put("ToolTip.foreground", TEXT);
        UIManager.put("List.selectionBackground", ACCENT);
        UIManager.put("List.selectionForeground", ON_ACCENT);
        UIManager.put("ScrollBar.thumb", BORDER);
        UIManager.put("ScrollBar.track", SURFACE);
    }
}
