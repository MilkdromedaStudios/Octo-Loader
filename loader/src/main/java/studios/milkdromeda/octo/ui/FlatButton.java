package studios.milkdromeda.octo.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.BorderFactory;
import javax.swing.JButton;

/** A flat, rounded button with the three states a pointer expects. */
public final class FlatButton extends JButton {
    /** How much weight the button carries on the page. */
    public enum Kind {
        /** The one thing the window is asking for. */
        PRIMARY,
        /** Everything else with a visible edge. */
        SECONDARY,
        /** Text only, for actions that should not compete. */
        GHOST
    }

    private final Kind kind;

    public FlatButton(String text, Kind kind) {
        super(text);
        this.kind = kind;

        setFont(Skin.ui(Font.BOLD, 12.5f));
        setBorder(BorderFactory.createEmptyBorder(9, 18, 9, 18));
        setContentAreaFilled(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setRolloverEnabled(true);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(Math.max(size.width, 96), Math.max(size.height, 36));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D canvas = Skin.smooth(graphics);

        canvas.setColor(background());
        canvas.fillRoundRect(0, 0, getWidth(), getHeight(), Skin.RADIUS - 2, Skin.RADIUS - 2);

        // A disabled button keeps its outline. Without one it is not a button
        // that cannot be pressed, it is a button that is not there.
        if (kind == Kind.SECONDARY || (!isEnabled() && kind != Kind.GHOST)) {
            canvas.setColor(Skin.BORDER);
            canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Skin.RADIUS - 2, Skin.RADIUS - 2);
        }

        // The focus ring is drawn rather than left to the look-and-feel: keyboard
        // users need to see where they are, and the platform's own ring is
        // invisible on a background this dark.
        if (isFocusOwner()) {
            canvas.setColor(Skin.ACCENT);
            canvas.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, Skin.RADIUS - 4, Skin.RADIUS - 4);
        }

        canvas.dispose();
        setForeground(foreground());
        super.paintComponent(graphics);
    }

    private Color background() {
        if (!isEnabled()) {
            return kind == Kind.GHOST ? new Color(0, 0, 0, 0) : Skin.SURFACE_RAISED;
        }

        boolean pressed = getModel().isArmed() && getModel().isPressed();
        boolean hover = getModel().isRollover();

        return switch (kind) {
            case PRIMARY -> pressed ? Skin.ACCENT_PRESSED : hover ? Skin.ACCENT_HOVER : Skin.ACCENT;
            case SECONDARY -> pressed ? Skin.BORDER : hover ? Skin.SURFACE_HOVER : Skin.SURFACE_RAISED;
            case GHOST -> pressed || hover ? Skin.SURFACE_RAISED : new Color(0, 0, 0, 0);
        };
    }

    private Color foreground() {
        if (!isEnabled()) {
            return Skin.TEXT_FAINT;
        }

        return switch (kind) {
            case PRIMARY -> Skin.ON_ACCENT;
            case SECONDARY -> Skin.TEXT;
            case GHOST -> getModel().isRollover() ? Skin.TEXT : Skin.TEXT_MUTED;
        };
    }
}
