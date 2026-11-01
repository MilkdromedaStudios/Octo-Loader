package studios.milkdromeda.octo.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.KeyStroke;

/** A card over the window, the window dimmed behind it: for a task that needs the player's attention. */
abstract class Sheet extends JPanel {
    protected final App app;
    private final JLayeredPane layers;
    private final int width;
    private final int height;
    protected final Widgets.Card card = new Widgets.Card(Style.WINDOW, 22, true);
    private Runnable onClose = () -> { };

    Sheet(App app, int width, int height) {
        super(null);
        this.app = app;
        this.layers = app.frame().getLayeredPane();
        this.width = width;
        this.height = height;
        setOpaque(false);
        // Clicks on the dimmed window go nowhere; Escape closes.
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                // Swallowed.
            }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                close();
            }
        });
        card.setLayout(new BorderLayout());
        add(card);
    }

    /** Runs when the sheet closes, however it does. */
    Sheet onClose(Runnable action) {
        onClose = action;
        return this;
    }

    void open() {
        setBounds(0, 0, layers.getWidth(), layers.getHeight());
        layers.add(this, JLayeredPane.MODAL_LAYER);
        doLayout();
        layers.revalidate();
        layers.repaint();
    }

    void close() {
        if (getParent() == null) {
            return;
        }

        layers.remove(this);
        layers.revalidate();
        layers.repaint();
        onClose.run();
    }

    @Override
    public void doLayout() {
        setBounds(0, 0, layers.getWidth(), layers.getHeight());
        int w = Math.min(width, getWidth() - 80);
        int h = Math.min(height, getHeight() - 80);
        card.setBounds((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
        card.validate();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Style.smooth(graphics);
        g.setColor(new Color(4, 5, 9, 190));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.dispose();
    }

    /** The sheet's title row, with a close button. */
    JComponent header(String title, String subtitle) {
        JPanel head = Widgets.clear(new BorderLayout(14, 0));
        head.setBorder(BorderFactory.createEmptyBorder(24, 28, 6, 20));
        JPanel names = Widgets.stack(5);
        JLabel label = new JLabel(title);
        label.setFont(Style.display(22f));
        label.setForeground(Style.TEXT);
        names.add(label);

        if (subtitle != null) {
            names.add(Widgets.paragraph(subtitle, 13f, Style.MUTED, 0));
        }

        head.add(names, BorderLayout.CENTER);
        Widgets.Button close = Widgets.iconButton(Icons.Glyph.CLOSE, "Close", Widgets.Kind.GHOST);
        close.addActionListener(event -> close());
        JPanel holder = Widgets.clear(new BorderLayout());
        holder.add(close, BorderLayout.NORTH);
        head.add(holder, BorderLayout.EAST);
        return head;
    }

    static JLabel status(String text, Color colour) {
        JLabel label = Widgets.text(text, 13f, Font.PLAIN, colour);
        return label;
    }
}
