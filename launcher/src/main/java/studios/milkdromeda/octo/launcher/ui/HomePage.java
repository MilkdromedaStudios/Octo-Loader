package studios.milkdromeda.octo.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Random;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import studios.milkdromeda.octo.installer.Mark;
import studios.milkdromeda.octo.launcher.News;
import studios.milkdromeda.octo.launcher.Play;
import studios.milkdromeda.octo.launcher.Versions;
import studios.milkdromeda.octo.launcher.Worlds;
import studios.milkdromeda.octo.launcher.mods.ModLibrary;
import studios.milkdromeda.octo.launcher.mods.Modrinth;

/** Home: the launch banner, what is new, mods to try, and the worlds. */
final class HomePage implements App.Page {
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault());

    private final App app;
    private final JPanel view = Widgets.clear(new BorderLayout(20, 0));
    private final JLabel heroTitle = Widgets.text("Minecraft", 34f, Font.BOLD, Style.TEXT);
    private final JLabel heroDetail = Widgets.text(" ", 14f, Font.PLAIN, Style.MUTED);
    private final Widgets.Button versionButton = Widgets.dropdown("Version");
    private final Widgets.Button launch = new Widgets.Button("LAUNCH GAME", Icons.of(Icons.Glyph.PLAY, 18,
            Color.WHITE), Widgets.Kind.PRIMARY, 14);
    private final JLabel launchNote = Widgets.text(" ", 12f, Font.PLAIN, Style.MUTED);
    private final JPanel news = Widgets.clear(new GridLayout(1, 3, 16, 0));
    private final JPanel popular = Widgets.clear(new GridLayout(1, 4, 14, 0));
    private final JPanel worlds = Widgets.column();
    private final JLabel modsSummary = Widgets.text(" ", 13f, Font.PLAIN, Style.MUTED);
    private List<Versions.Version> versions = List.of();

    HomePage(App app) {
        this.app = app;
        view.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));

        JPanel main = Widgets.column();
        main.add(hero());
        main.add(Box.createVerticalStrut(26));
        main.add(header("Latest news", "All releases", () -> browse("https://github.com/MilkdromedaStudios/"
                + "Octo-Loader/releases")));
        news.setAlignmentX(Component.LEFT_ALIGNMENT);
        news.setMaximumSize(new Dimension(Integer.MAX_VALUE, 210));
        main.add(news);
        main.add(Box.createVerticalStrut(26));
        main.add(header("Popular mods", "Browse all mods", () -> app.go("Mods")));
        popular.setAlignmentX(Component.LEFT_ALIGNMENT);
        popular.setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        main.add(popular);
        main.add(Box.createVerticalGlue());

        JPanel holder = new Widgets.Tracking();
        holder.add(main, BorderLayout.NORTH);
        view.add(Widgets.scroll(holder), BorderLayout.CENTER);
        view.add(side(), BorderLayout.EAST);

        launch.setFont(Style.font(Font.BOLD, 16f));
        launch.setBorder(BorderFactory.createEmptyBorder(15, 34, 15, 38));
        launch.addActionListener(event -> launch());
        versionButton.addActionListener(event -> chooseVersion());
        loadNews();
        loadPopular();
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void refresh() {
        app.run(() -> new Object[] { Versions.list(app.settings().gameDir(), false),
                Worlds.list(app.settings().gameDir()), new ModLibrary(app.settings().gameDir()).list() },
                loaded -> {
                    @SuppressWarnings("unchecked")
                    List<Versions.Version> versionList = (List<Versions.Version>) loaded[0];
                    @SuppressWarnings("unchecked")
                    List<Worlds.World> worldList = (List<Worlds.World>) loaded[1];
                    @SuppressWarnings("unchecked")
                    List<ModLibrary.InstalledMod> mods = (List<ModLibrary.InstalledMod>) loaded[2];
                    versions = versionList;

                    if (app.settings().selectedVersion.isBlank() && !versionList.isEmpty()) {
                        app.settings().selectedVersion = versionList.stream().filter(Versions.Version::withOcto)
                                .map(Versions.Version::id).findFirst()
                                .orElse(versionList.stream().filter(Versions.Version::isRelease)
                                        .map(Versions.Version::id).findFirst().orElse(""));
                    }

                    showVersion();
                    showWorlds(worldList);
                    long on = mods.stream().filter(ModLibrary.InstalledMod::enabled).count();
                    modsSummary.setText("<html><b style='color:" + Style.hex(Style.TEXT) + "'>" + on
                            + "</b> mods on" + (mods.size() > on ? ", " + (mods.size() - on) + " off" : "")
                            + "</html>");
                    String last = worldList.isEmpty() ? "No worlds yet" : "Last played: " + worldList.get(0).name();
                    heroDetail.setText(on + " mods  ·  " + last);
                });
    }

    // --------------------------------------------------------------- hero

    private JComponent hero() {
        Hero hero = new Hero();
        hero.setLayout(new BorderLayout());
        hero.setAlignmentX(Component.LEFT_ALIGNMENT);
        hero.setPreferredSize(new Dimension(600, 300));
        hero.setMaximumSize(new Dimension(Integer.MAX_VALUE, 300));
        hero.setBorder(BorderFactory.createEmptyBorder(34, 38, 30, 38));

        JPanel text = Widgets.column();
        JLabel eyebrow = Widgets.pill("OCTO LOADER", Style.CYAN);
        eyebrow.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(eyebrow);
        text.add(Box.createVerticalStrut(14));
        heroTitle.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(heroTitle);
        text.add(Box.createVerticalStrut(6));
        heroDetail.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(heroDetail);
        text.add(Box.createVerticalGlue());

        JPanel actions = Widgets.row(12);
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        actions.add(launch);
        versionButton.setBorder(BorderFactory.createEmptyBorder(15, 18, 15, 18));
        versionButton.setFont(Style.font(Font.BOLD, 14f));
        actions.add(versionButton);
        text.add(actions);
        text.add(Box.createVerticalStrut(10));
        launchNote.setText("Opens the Minecraft Launcher with Octo selected: sign in and play there.");
        launchNote.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(launchNote);
        hero.add(text, BorderLayout.CENTER);
        return hero;
    }

    /** The banner art: glows in the accent colours, floating blocks, and the mark. */
    private static final class Hero extends JPanel {
        private final Image mark = Mark.image(220);

        Hero() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Style.smooth(graphics);
            int w = getWidth();
            int h = getHeight();
            g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h, 22, 22));
            g.setPaint(new GradientPaint(0, 0, new Color(0x1B, 0x14, 0x3D), w, h, new Color(0x08, 0x1E, 0x33)));
            g.fillRect(0, 0, w, h);
            glow(g, w * 0.18f, h * 0.1f, Math.max(w, h) * 0.55f, Style.alpha(Style.VIOLET, 110));
            glow(g, w * 0.92f, h * 0.95f, Math.max(w, h) * 0.5f, Style.alpha(Style.CYAN, 90));

            // A loose drift of blocks to the right, in perspective.
            Random random = new Random(7);

            for (int i = 0; i < 18; i++) {
                float size = 14 + random.nextFloat() * 34;
                float x = w * 0.5f + random.nextFloat() * w * 0.5f;
                float y = random.nextFloat() * h;
                block(g, x, y, size, random.nextBoolean() ? Style.VIOLET : Style.CYAN, 40 + random.nextInt(70));
            }

            g.drawImage(mark, w - 270, h / 2 - 110, 220, 220, null);
            g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 120), w * 0.6f, 0, new Color(0, 0, 0, 0)));
            g.fillRect(0, 0, w, h);
            g.setClip(null);
            g.setColor(Style.alpha(Color.WHITE, 22));
            g.drawRoundRect(0, 0, w - 1, h - 1, 22, 22);
            g.dispose();
        }

        private static void glow(Graphics2D g, float x, float y, float radius, Color colour) {
            g.setPaint(new RadialGradientPaint(new Point2D.Float(x, y), radius, new float[] { 0f, 1f },
                    new Color[] { colour, Style.alpha(colour, 0) }, MultipleGradientPaint.CycleMethod.NO_CYCLE));
            g.fillRect(0, 0, (int) (x + radius) + 10, (int) (y + radius) + 10);
        }

        /** An isometric cube: top, left and right faces in three shades. */
        private static void block(Graphics2D g, float x, float y, float s, Color colour, int alpha) {
            float half = s / 2;
            float quarter = s / 4;
            Path2D top = new Path2D.Float();
            top.moveTo(x, y - quarter);
            top.lineTo(x + half, y);
            top.lineTo(x, y + quarter);
            top.lineTo(x - half, y);
            top.closePath();
            Path2D left = new Path2D.Float();
            left.moveTo(x - half, y);
            left.lineTo(x, y + quarter);
            left.lineTo(x, y + quarter + half);
            left.lineTo(x - half, y + half);
            left.closePath();
            Path2D right = new Path2D.Float();
            right.moveTo(x + half, y);
            right.lineTo(x, y + quarter);
            right.lineTo(x, y + quarter + half);
            right.lineTo(x + half, y + half);
            right.closePath();
            g.setColor(Style.alpha(Style.mix(colour, Color.WHITE, 0.35f), alpha));
            g.fill(top);
            g.setColor(Style.alpha(colour, alpha));
            g.fill(left);
            g.setColor(Style.alpha(Style.mix(colour, Color.BLACK, 0.35f), alpha));
            g.fill(right);
        }
    }

    private void showVersion() {
        String version = app.settings().selectedVersion;
        heroTitle.setText(version.isBlank() ? "Minecraft" : "Minecraft " + version);
        versionButton.setText(version.isBlank() ? "Choose version" : version);
    }

    private void chooseVersion() {
        JPopupMenu menu = new JPopupMenu();
        menu.setBorder(BorderFactory.createLineBorder(Style.LINE));
        versions.stream().filter(version -> version.withOcto() || version.isRelease()).limit(14).forEach(version -> {
            JMenuItem item = new JMenuItem(version.id() + (version.withOcto() ? "    (Octo installed)" : ""));
            item.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 24));
            item.addActionListener(event -> {
                app.settings().selectedVersion = version.id();
                app.save();
                showVersion();
            });
            menu.add(item);
        });
        JMenuItem all = new JMenuItem("All versions...");
        all.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 24));
        all.addActionListener(event -> app.go("Versions"));
        menu.add(all);
        menu.show(versionButton, 0, versionButton.getHeight() + 6);
    }

    private void launch() {
        launch.setEnabled(false);
        launch.setText("PREPARING...");
        app.run(() -> Play.prepareAndOpen(app.settings(), app.settings().selectedVersion, message -> { }), result -> {
            app.toast(result, Style.GREEN);
            app.changed();
        });

        javax.swing.Timer reset = new javax.swing.Timer(2500, event -> {
            launch.setEnabled(true);
            launch.setText("LAUNCH GAME");
        });
        reset.setRepeats(false);
        reset.start();
    }

    // ------------------------------------------------------------- news

    private JComponent header(String title, String link, Runnable onLink) {
        JPanel row = Widgets.clear(new BorderLayout());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        row.add(Widgets.text(title, 17f, Font.BOLD, Style.TEXT), BorderLayout.WEST);
        Widgets.Button more = Widgets.button(link, null, Widgets.Kind.GHOST);
        more.setForeground(Style.MUTED);
        more.setFont(Style.font(Font.PLAIN, 12.5f));
        more.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        more.addActionListener(event -> onLink.run());
        row.add(more, BorderLayout.EAST);
        return row;
    }

    private void loadNews() {
        app.run(() -> News.latest(3), items -> {
            news.removeAll();

            for (int i = 0; i < 3; i++) {
                news.add(i < items.size() ? newsCard(items.get(i), i) : Widgets.clear(new BorderLayout()));
            }

            news.revalidate();
            news.repaint();
        });
    }

    private JComponent newsCard(News.Item item, int index) {
        Widgets.Card card = new Widgets.Card(Style.PANEL, 16, true).hoverable(() -> browse(item.url()));
        card.setLayout(new BorderLayout());
        Color[][] palettes = { { Style.VIOLET, new Color(0x2A, 0x1A, 0x6E) }, { Style.CYAN, new Color(0x06, 0x30, 0x52) },
                { new Color(0xE0, 0x5C, 0xFF), new Color(0x3A, 0x10, 0x4A) } };
        Color[] palette = palettes[index % palettes.length];
        JPanel art = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Style.smooth(graphics);
                int w = getWidth();
                int h = getHeight() + 16;
                g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h, 16, 16));
                g.setPaint(new GradientPaint(0, 0, palette[1], w, h, Style.mix(palette[0], Color.BLACK, 0.2f)));
                g.fillRect(0, 0, w, h);
                g.setColor(Style.alpha(Color.WHITE, 18));

                for (int i = -h; i < w; i += 18) {
                    g.drawLine(i, h, i + h, 0);
                }

                g.setFont(Style.font(Font.BOLD, 13f));
                g.setColor(Style.alpha(Color.WHITE, 220));
                g.drawString(index == 0 ? "NEW RELEASE" : "RELEASE", 16, 28);
                g.dispose();
            }
        };
        art.setPreferredSize(new Dimension(10, 74));
        art.setOpaque(false);
        card.add(art, BorderLayout.NORTH);

        JPanel body = Widgets.column();
        body.setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));
        JLabel title = Widgets.wrapped("<b>" + escape(item.title()) + "</b>", 13.5f, Style.TEXT, 170);
        body.add(title);
        body.add(Box.createVerticalStrut(4));
        body.add(Widgets.text(DATE.format(item.published()), 11.5f, Font.PLAIN, Style.FAINT));
        body.add(Box.createVerticalStrut(6));
        body.add(Widgets.wrapped(escape(item.summary()), 12f, Style.MUTED, 170));
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    // ----------------------------------------------------------- popular

    private void loadPopular() {
        app.run(() -> Modrinth.search("", null, null, "downloads", 0, 4), page -> {
            popular.removeAll();

            for (Modrinth.Project project : page.projects()) {
                popular.add(popularCard(project));
            }

            popular.revalidate();
            popular.repaint();
        });
    }

    private JComponent popularCard(Modrinth.Project project) {
        Widgets.Card card = Widgets.card();
        card.setLayout(new BorderLayout(0, 10));
        card.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JLabel icon = new JLabel();
        Images.load(project.iconUrl(), project.title(), project.color() == 0 ? null : new Color(project.color()),
                44, 12, image -> icon.setIcon(new javax.swing.ImageIcon(image)));
        JPanel top = Widgets.clear(new BorderLayout(10, 0));
        top.add(icon, BorderLayout.WEST);
        JPanel name = Widgets.column();
        name.add(Widgets.text(shorten(project.title(), 18), 13.5f, Font.BOLD, Style.TEXT));
        name.add(Box.createVerticalStrut(3));
        name.add(Widgets.text(Widgets.count(project.downloads()) + " downloads", 11.5f, Font.PLAIN, Style.FAINT));
        top.add(name, BorderLayout.CENTER);
        card.add(top, BorderLayout.NORTH);
        card.add(ModsPage.installButton(app, project, true), BorderLayout.SOUTH);
        return card;
    }

    // -------------------------------------------------------------- side

    private JComponent side() {
        JPanel side = Widgets.column();
        side.setPreferredSize(new Dimension(300, 10));

        Widgets.Card worldCard = Widgets.card();
        worldCard.setLayout(new BorderLayout(0, 8));
        JPanel head = Widgets.clear(new BorderLayout());
        head.add(Widgets.text("Your worlds", 15f, Font.BOLD, Style.TEXT), BorderLayout.WEST);
        Widgets.Button manage = Widgets.button("Manage", null, Widgets.Kind.GHOST);
        manage.setForeground(Style.MUTED);
        manage.setFont(Style.font(Font.PLAIN, 12f));
        manage.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        manage.addActionListener(event -> app.go("Worlds"));
        head.add(manage, BorderLayout.EAST);
        worldCard.add(head, BorderLayout.NORTH);
        worldCard.add(worlds, BorderLayout.CENTER);
        worldCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(worldCard);
        side.add(Box.createVerticalStrut(16));

        Widgets.Card modsCard = Widgets.card();
        modsCard.setLayout(new BorderLayout(0, 10));
        modsCard.add(Widgets.text("Mods", 15f, Font.BOLD, Style.TEXT), BorderLayout.NORTH);
        modsCard.add(modsSummary, BorderLayout.CENTER);
        Widgets.Button openMods = Widgets.button("Open mod manager", Icons.Glyph.MODS, Widgets.Kind.SECONDARY);
        openMods.addActionListener(event -> app.go("Mods"));
        modsCard.add(openMods, BorderLayout.SOUTH);
        modsCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        modsCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
        side.add(modsCard);
        side.add(Box.createVerticalGlue());
        return side;
    }

    private void showWorlds(List<Worlds.World> list) {
        worlds.removeAll();

        if (list.isEmpty()) {
            worlds.add(Widgets.wrapped("Worlds you play appear here, with their backups.", 12.5f, Style.MUTED, 230));
        }

        for (Worlds.World world : list.stream().limit(6).toList()) {
            worlds.add(worldRow(world));
            worlds.add(Box.createVerticalStrut(4));
        }

        worlds.revalidate();
        worlds.repaint();
    }

    static Image worldIcon(Worlds.World world, int size) {
        java.nio.file.Path icon = world.folder().resolve("icon.png");

        try {
            if (java.nio.file.Files.isRegularFile(icon)) {
                return Images.fromBytes(java.nio.file.Files.readAllBytes(icon), world.name(), size, 10);
            }
        } catch (java.io.IOException e) {
            // A tile, then.
        }

        return Widgets.monogram(world.name(), Widgets.colourFor(world.name()), size, 10);
    }

    private JComponent worldRow(Worlds.World world) {
        Widgets.Card row = new Widgets.Card(Style.PANEL, 12, false).hoverable(() -> app.go("Worlds"));
        row.setLayout(new BorderLayout(10, 0));
        row.setBorder(BorderFactory.createEmptyBorder(7, 6, 7, 6));
        row.add(Widgets.picture(worldIcon(world, 38)), BorderLayout.WEST);
        JPanel text = Widgets.column();
        text.add(Widgets.text(shorten(world.name(), 22), 13f, Font.BOLD, Style.TEXT));
        text.add(Box.createVerticalStrut(2));
        text.add(Widgets.text(ago(world.lastPlayed()) + "  ·  " + world.backups() + " backups", 11.5f, Font.PLAIN,
                Style.FAINT));
        row.add(text, BorderLayout.CENTER);

        if (world.needsAttention()) {
            JLabel alert = new JLabel(Icons.of(Icons.Glyph.ALERT, 16, Style.AMBER));
            alert.setToolTipText("Missing content from " + world.missingMods() + ": Octo is protecting it");
            row.add(alert, BorderLayout.EAST);
        }

        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 56));
        return row;
    }

    static String ago(Instant when) {
        Duration since = Duration.between(when, Instant.now());

        if (since.toMinutes() < 1) {
            return "just now";
        }

        if (since.toHours() < 1) {
            return since.toMinutes() + " min ago";
        }

        if (since.toDays() < 1) {
            return since.toHours() + " h ago";
        }

        if (since.toDays() < 30) {
            return since.toDays() + (since.toDays() == 1 ? " day ago" : " days ago");
        }

        return DATE.format(when);
    }

    static String shorten(String text, int length) {
        return text == null ? "" : text.length() > length ? text.substring(0, length - 1) + "…" : text;
    }

    static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static void browse(String url) {
        try {
            Desktop.getDesktop().browse(URI.create(url));
        } catch (Exception e) {
            // No browser: nothing to do.
        }
    }
}
