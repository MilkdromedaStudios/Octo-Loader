package studios.milkdromeda.octo.installer;

import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.List;

import javax.swing.UIManager;

/**
 * The installer's palette and type.
 *
 * <p>Hand-rolled rather than pulled from a look-and-feel library, because the
 * installer's whole promise is that it works with nothing else present — it
 * carries the loader inside itself and needs no network — and a three-megabyte
 * theming dependency for the sake of rounded corners would be a poor trade.
 *
 * <p>The scheme is dark on purpose: this sits next to a game launcher, and the
 * contrast ratios below are chosen to stay legible rather than to look dim.
 *
 * <p>These are the same numbers as the loader's
 * {@code studios.milkdromeda.octo.ui.Skin}, kept in step by hand. The two jars
 * ship separately and neither can depend on the other without swallowing it
 * whole, so the installer and the report window share a design rather than a
 * class: a player who installs Octo and then reads its report should not be
 * looking at two different applications.
 */
final class Theme {
    /** Window and page background. */
    static final Color BACKGROUND = new Color(0x0F, 0x11, 0x16);
    /** Cards and inputs sitting on the background. */
    static final Color SURFACE = new Color(0x17, 0x1A, 0x21);
    /** Rows and inputs raised off a card. */
    static final Color SURFACE_RAISED = new Color(0x1E, 0x22, 0x2B);
    static final Color SURFACE_HOVER = new Color(0x26, 0x2B, 0x36);
    static final Color BORDER = new Color(0x2A, 0x2F, 0x3A);

    static final Color TEXT = new Color(0xE9, 0xEC, 0xF2);
    static final Color TEXT_MUTED = new Color(0x98, 0xA0, 0xB0);
    static final Color TEXT_FAINT = new Color(0x6C, 0x74, 0x84);

    /** The one saturated colour, used for the primary action and focus rings. */
    static final Color ACCENT = new Color(0x7C, 0x6B, 0xF5);
    static final Color ACCENT_HOVER = new Color(0x8E, 0x7E, 0xF7);
    static final Color ACCENT_PRESSED = new Color(0x6A, 0x59, 0xE0);
    static final Color ON_ACCENT = new Color(0x0E, 0x0F, 0x14);

    static final Color SUCCESS = new Color(0x4A, 0xDE, 0x9B);
    static final Color DANGER = new Color(0xFF, 0x6B, 0x6B);

    static final int RADIUS = 12;

    private Theme() {
    }

    static Font ui(int style, float size) {
        return base().deriveFont(style, size);
    }

    static Font mono(float size) {
        return new Font(pick(List.of("JetBrains Mono", "Cascadia Mono", "SF Mono", "Consolas", "Menlo",
                "DejaVu Sans Mono"), Font.MONOSPACED), Font.PLAIN, 12).deriveFont(size);
    }

    static java.awt.Graphics2D smooth(java.awt.Graphics graphics) {
        java.awt.Graphics2D canvas = (java.awt.Graphics2D) graphics.create();
        canvas.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        canvas.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL,
                java.awt.RenderingHints.VALUE_STROKE_PURE);
        canvas.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return canvas;
    }

    private static Font base() {
        // The platform's own UI face first, so the window looks native-adjacent
        // rather than like a 2004 Java application.
        return new Font(pick(List.of("Segoe UI Variable Text", "Segoe UI", "SF Pro Text", "Inter", "Ubuntu",
                "Noto Sans", "DejaVu Sans"), Font.SANS_SERIF), Font.PLAIN, 13);
    }

    private static String pick(List<String> candidates, String fallback) {
        List<String> available = List.of(GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames());

        for (String candidate : candidates) {
            if (available.contains(candidate)) {
                return candidate;
            }
        }

        return fallback;
    }

    /**
     * Teaches the shared dialogs — the folder chooser, the confirmation box —
     * the same colours, so they do not arrive as a white rectangle in the middle
     * of a dark window.
     */
    static void applyToDialogs() {
        Font font = ui(Font.PLAIN, 13f);

        for (String key : List.of("Panel", "OptionPane", "Label", "Button", "ComboBox", "TextField", "List",
                "ScrollPane", "Viewport", "FileChooser", "ToggleButton", "MenuBar", "Menu", "MenuItem",
                "RadioButton", "CheckBox", "TitledBorder", "ToolTip", "Table", "TableHeader", "Tree")) {
            UIManager.put(key + ".background", SURFACE);
            UIManager.put(key + ".foreground", TEXT);
            UIManager.put(key + ".font", font);
        }

        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("OptionPane.background", SURFACE);
        UIManager.put("FileChooser.listViewBackground", SURFACE);
        UIManager.put("TextField.caretForeground", TEXT);
        UIManager.put("List.selectionBackground", ACCENT);
        UIManager.put("List.selectionForeground", ON_ACCENT);
        UIManager.put("ComboBox.selectionBackground", ACCENT);
        UIManager.put("ComboBox.selectionForeground", ON_ACCENT);
        UIManager.put("ScrollBar.thumb", BORDER);
        UIManager.put("ScrollBar.track", BACKGROUND);
        UIManager.put("ToolTip.background", SURFACE_HOVER);
    }
}
