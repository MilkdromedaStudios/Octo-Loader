package studios.milkdromeda.octo.installer;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.BorderFactory;
import javax.swing.JButton;

/** A flat, rounded button with the three states a pointer expects. */
final class FlatButton extends JButton {
    /** How much weight the button carries on the page. */
    enum Kind {
        /** The one thing the user came here to do. */
        PRIMARY,
        /** Everything else. */
        SECONDARY,
        /** Something that removes what they installed. */
        DANGER
    }

    private final Kind kind;

    FlatButton(String text, Kind kind) {
        super(text);
        this.kind = kind;

        setFont(Theme.ui(Font.BOLD, 13f));
        setForeground(kind == Kind.PRIMARY ? Theme.ON_ACCENT : Theme.TEXT);
        setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));
        setContentAreaFilled(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setRolloverEnabled(true);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        return new Dimension(Math.max(size.width, 108), Math.max(size.height, 38));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        canvas.setColor(background());
        canvas.fillRoundRect(0, 0, getWidth(), getHeight(), Theme.RADIUS, Theme.RADIUS);

        if (kind != Kind.PRIMARY) {
            canvas.setColor(kind == Kind.DANGER && isEnabled() ? Theme.DANGER.darker() : Theme.BORDER);
            canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Theme.RADIUS, Theme.RADIUS);
        }

        if (isFocusOwner()) {
            canvas.setColor(Theme.ACCENT);
            canvas.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, Theme.RADIUS - 2, Theme.RADIUS - 2);
        }

        canvas.dispose();
        setForeground(foreground());
        super.paintComponent(graphics);
    }

    private Color background() {
        if (!isEnabled()) {
            return Theme.SURFACE;
        }

        boolean pressed = getModel().isArmed() && getModel().isPressed();
        boolean hover = getModel().isRollover();

        return switch (kind) {
            case PRIMARY -> pressed ? Theme.ACCENT_PRESSED : hover ? Theme.ACCENT_HOVER : Theme.ACCENT;
            case DANGER, SECONDARY -> pressed ? Theme.BORDER : hover ? Theme.SURFACE_HOVER : Theme.SURFACE;
        };
    }

    private Color foreground() {
        if (!isEnabled()) {
            return Theme.TEXT_MUTED;
        }

        return switch (kind) {
            case PRIMARY -> Theme.ON_ACCENT;
            case DANGER -> Theme.DANGER;
            case SECONDARY -> Theme.TEXT;
        };
    }
}
