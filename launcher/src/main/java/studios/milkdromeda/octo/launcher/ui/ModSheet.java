package studios.milkdromeda.octo.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
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

import studios.milkdromeda.octo.launcher.mods.Modrinth;

/**
 * A mod's own page, over the window: its pictures, what it is, what it runs
 * on, and Install. Closes on its button, Escape, or a click outside it.
 */
final class ModSheet extends JPanel {
    private final App app;
    private final JLayeredPane layers;
    private final Modrinth.Project project;
    private final JPanel sheet = new Widgets.Card(Style.WINDOW, 22, true);
    private final JPanel body = Widgets.stack(18);

    private ModSheet(App app, JLayeredPane layers, Modrinth.Project project) {
        super(null);
        this.app = app;
        this.layers = layers;
        this.project = project;
        setOpaque(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!sheet.getBounds().contains(event.getPoint())) {
                    close();
                }
            }
        });
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                close();
            }
        });

        sheet.setLayout(new BorderLayout(0, 0));
        sheet.add(header(), BorderLayout.NORTH);
        body.setBorder(BorderFactory.createEmptyBorder(4, 28, 28, 28));
        body.add(new Skeleton(190, false));
        body.add(new Skeleton(120, false));
        sheet.add(Widgets.page(body), BorderLayout.CENTER);
        add(sheet);
    }

    static void show(App app, Modrinth.Project project) {
        JLayeredPane layers = app.frame().getLayeredPane();
        ModSheet overlay = new ModSheet(app, layers, project);
        overlay.setBounds(0, 0, layers.getWidth(), layers.getHeight());
        layers.add(overlay, JLayeredPane.MODAL_LAYER);
        overlay.layoutSheet();
        overlay.load();
        layers.revalidate();
        layers.repaint();
    }

    private void close() {
        layers.remove(this);
        layers.revalidate();
        layers.repaint();
    }

    @Override
    public void doLayout() {
        layoutSheet();
    }

    private void layoutSheet() {
        setBounds(0, 0, layers.getWidth(), layers.getHeight());
        int width = Math.min(860, getWidth() - 120);
        int height = Math.min(700, getHeight() - 110);
        sheet.setBounds((getWidth() - width) / 2, (getHeight() - height) / 2 + 12, width, height);
        sheet.validate();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = Style.smooth(graphics);
        g.setColor(new Color(4, 5, 9, 185));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.dispose();
    }

    private JComponent header() {
        JPanel head = Widgets.clear(new BorderLayout(18, 0));
        head.setBorder(BorderFactory.createEmptyBorder(26, 28, 20, 22));
        JLabel icon = new JLabel();
        Images.load(project.iconUrl(), project.title(), project.color() == 0 ? null : new Color(project.color()),
                76, 18, image -> icon.setIcon(new javax.swing.ImageIcon(image)));
        JPanel iconHolder = Widgets.clear(new BorderLayout());
        iconHolder.add(icon, BorderLayout.NORTH);
        head.add(iconHolder, BorderLayout.WEST);

        JPanel names = Widgets.stack(6);
        JLabel title = new JLabel(project.title());
        title.setFont(Style.display(26f));
        title.setForeground(Style.TEXT);
        names.add(title);
        names.add(Widgets.line((project.author() == null ? "" : "by " + project.author() + "   ·   ")
                + Widgets.count(project.downloads()) + " downloads", Style.font(Font.PLAIN, 13f), Style.MUTED));
        JPanel pills = Widgets.flow(6, 6);

        for (String loader : project.loaders()) {
            pills.add(Widgets.pill(ModsPage.loaderName(loader), ModsPage.loaderColour(loader)));
        }

        names.add(pills);
        head.add(names, BorderLayout.CENTER);

        JPanel actions = Widgets.row(8);
        actions.add(ModsPage.installButton(app, project));
        Widgets.Button page = Widgets.iconButton(Icons.Glyph.EXTERNAL, "Open on Modrinth", Widgets.Kind.SECONDARY);
        page.addActionListener(event -> HomePage.browse(project.pageUrl()));
        actions.add(page);
        Widgets.Button close = Widgets.iconButton(Icons.Glyph.CLOSE, "Close", Widgets.Kind.GHOST);
        close.addActionListener(event -> close());
        actions.add(close);
        JPanel actionHolder = Widgets.clear(new BorderLayout());
        actionHolder.add(actions, BorderLayout.NORTH);
        head.add(actionHolder, BorderLayout.EAST);
        return head;
    }

    private void load() {
        app.run(() -> Modrinth.details(project.id()), details -> {
            body.removeAll();

            if (!details.gallery().isEmpty()) {
                JPanel gallery = Widgets.grid(220, 3, 12);

                for (String url : details.gallery().stream().limit(3).toList()) {
                    gallery.add(picture(url));
                }

                body.add(gallery);
            }

            JPanel facts = Widgets.grid(150, 4, 10);
            facts.add(fact("Downloads", Widgets.count(details.project().downloads())));
            facts.add(fact("Followers", Widgets.count(details.followers())));
            facts.add(fact("Updated", details.updated() == null ? "-"
                    : HomePage.ago(java.time.Instant.parse(details.updated()))));
            facts.add(fact("Minecraft", newest(details.project().gameVersions())));
            body.add(facts);

            if (!details.project().categories().isEmpty()) {
                JPanel categories = Widgets.flow(6, 6);
                details.project().categories().stream().filter(name -> !Modrinth.LOADERS.contains(name))
                        .forEach(name -> categories.add(Widgets.pill(name, Style.MUTED)));
                body.add(categories);
            }

            String description = Modrinth.plain(details.body());
            body.add(new Text(description.isBlank() ? details.project().description()
                    : description.length() > 3500 ? description.substring(0, 3500) + "…" : description,
                    Style.font(Font.PLAIN, 13.5f), Style.MUTED, 0));

            JPanel links = Widgets.flow(8, 8);
            link(links, "Source code", details.sourceUrl());
            link(links, "Issues", details.issuesUrl());
            link(links, "Discord", details.discordUrl());
            link(links, "Modrinth page", project.pageUrl());
            body.add(links);
            body.revalidate();
            body.repaint();
        }, failure -> {
            body.removeAll();
            body.add(ModsPage.empty("This mod's page could not be loaded", project.description()));
            body.revalidate();
        });
    }

    private static String newest(java.util.List<String> versions) {
        String best = "-";
        String key = "";

        for (String version : versions) {
            String candidate = version.matches("\\d+(\\.\\d+)*") ? String.format("%08d",
                    java.util.Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt)
                            .reduce(0, (a, b) -> a * 100 + b)) : "";

            if (candidate.compareTo(key) > 0) {
                key = candidate;
                best = version;
            }
        }

        return best;
    }

    private static JComponent picture(String url) {
        JComponent frame = new JComponent() {
            private java.awt.Image image;

            {
                Images.cover(url, 420, 236, 14, loaded -> {
                    image = loaded;
                    repaint();
                });
            }

            @Override
            public Dimension getPreferredSize() {
                int width = getWidth() > 0 ? getWidth() : 260;
                return new Dimension(width, width * 9 / 16);
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Style.smooth(graphics);
                g.setColor(Style.RAISED);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);

                if (image != null) {
                    g.drawImage(image, 0, 0, getWidth(), getHeight(), null);
                }

                g.dispose();
            }
        };
        return frame;
    }

    private static JComponent fact(String label, String value) {
        Widgets.Card tile = new Widgets.Card(Style.PANEL, 12, true);
        tile.setLayout(new BorderLayout(0, 2));
        tile.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        tile.add(Widgets.line(label, Style.font(Font.PLAIN, 11.5f), Style.FAINT), BorderLayout.NORTH);
        JLabel number = new JLabel(value);
        number.setFont(Style.display(17f));
        number.setForeground(Style.TEXT);
        tile.add(number, BorderLayout.CENTER);
        return tile;
    }

    private static void link(JPanel links, String label, String url) {
        if (url == null || url.isBlank()) {
            return;
        }

        Widgets.Button button = Widgets.button(label, Icons.Glyph.EXTERNAL, Widgets.Kind.SECONDARY);
        button.addActionListener(event -> HomePage.browse(url));
        links.add(button);
    }

    @Override
    public boolean isOptimizedDrawingEnabled() {
        return false;
    }

    /** For layouts asking: the sheet fills the window. */
    @Override
    public Component.BaselineResizeBehavior getBaselineResizeBehavior() {
        return Component.BaselineResizeBehavior.OTHER;
    }
}
