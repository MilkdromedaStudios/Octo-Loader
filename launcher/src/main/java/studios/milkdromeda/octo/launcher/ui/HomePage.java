package studios.milkdromeda.octo.launcher.ui;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
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
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

import studios.milkdromeda.octo.common.WorldBackups;
import studios.milkdromeda.octo.installer.Mark;
import studios.milkdromeda.octo.launcher.Launcher;
import studios.milkdromeda.octo.launcher.News;
import studios.milkdromeda.octo.launcher.Play;
import studios.milkdromeda.octo.launcher.Updates;
import studios.milkdromeda.octo.launcher.Versions;
import studios.milkdromeda.octo.launcher.Worlds;
import studios.milkdromeda.octo.launcher.game.GameFiles;
import studios.milkdromeda.octo.launcher.mods.ModLibrary;
import studios.milkdromeda.octo.launcher.mods.Modrinth;

/** Home: the launch banner, what is new, mods to try, and the worlds. */
final class HomePage implements App.Page {
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneId.systemDefault());

    private final App app;
    private final JPanel view = Widgets.clear(new BorderLayout(22, 0));
    private final JLabel heroTitle = new JLabel("Minecraft");
    private final Text heroDetail = Widgets.line(" ", Style.font(Font.PLAIN, 14.5f), Style.MUTED);
    private final Widgets.Button versionButton = Widgets.dropdown("Version");
    private final Widgets.Button launch = new Widgets.Button("Launch game", Icons.of(Icons.Glyph.PLAY, 18,
            Color.WHITE), Widgets.Kind.PRIMARY, 14);
    private final JPanel news = Widgets.grid(240, 3, 16);
    private final JPanel popular = Widgets.grid(230, 3, 14);
    private final JPanel worlds = Widgets.stack(4);
    private final JPanel modsBody = Widgets.stack(10);
    private final JPanel octoBody = Widgets.stack(8);
    private final Text launchHint = Widgets.line(" ", Style.font(Font.PLAIN, 12.5f), Style.MUTED);
    private final ProgressBar progressBar = new ProgressBar();
    private Play.Running running;
    private boolean preparing;
    private List<Versions.Version> versions = List.of();
    private Hero heroArt;
    private boolean mobLoaded;

    HomePage(App app) {
        this.app = app;
        view.setBorder(BorderFactory.createEmptyBorder(22, 24, 0, 24));

        JPanel main = Widgets.stack(0);
        main.setBorder(BorderFactory.createEmptyBorder(0, 0, 24, 4));
        main.add(hero());
        main.add(gap(30));
        main.add(header("Latest news", "All releases", () -> browse("https://github.com/MilkdromedaStudios/"
                + "Octo-Loader/releases")));
        main.add(news);
        main.add(gap(30));
        main.add(header("Popular mods", "Browse all mods", () -> app.go("Mods")));
        main.add(popular);
        view.add(Widgets.page(main), BorderLayout.CENTER);

        JComponent side = Widgets.page(side());
        side.setPreferredSize(new Dimension(300, 10));
        view.add(side, BorderLayout.EAST);

        launch.setFont(Style.strong(16f));
        launch.setBorder(BorderFactory.createEmptyBorder(15, 30, 15, 34));
        launch.addActionListener(event -> launch());
        versionButton.setFont(Style.strong(14f));
        versionButton.setBorder(BorderFactory.createEmptyBorder(15, 18, 15, 16));
        versionButton.addActionListener(event -> chooseVersion());

        for (int i = 0; i < 3; i++) {
            news.add(new Skeleton(176, false));
        }

        for (int i = 0; i < 6; i++) {
            popular.add(new Skeleton(150, true));
        }

        loadNews();
        loadPopular();
    }

    private static JComponent gap(int height) {
        JPanel gap = Widgets.clear(null);
        gap.setPreferredSize(new Dimension(1, height));
        return gap;
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
                    showLaunchHint();
                    loadMob();
                    showWorlds(worldList);
                    showMods(mods);
                    showOcto(worldList);
                    long on = mods.stream().filter(ModLibrary.InstalledMod::enabled).count();
                    heroDetail.setText(on + (on == 1 ? " mod" : " mods") + "   ·   " + (worldList.isEmpty()
                            ? "No worlds yet" : "Last played: " + worldList.get(0).name()));
                    view.revalidate();
                });
    }

    // --------------------------------------------------------------- hero

    private JComponent hero() {
        Hero hero = new Hero();
        heroArt = hero;
        hero.setBorder(BorderFactory.createEmptyBorder(32, 36, 28, 36));

        JPanel text = Widgets.stack(0);
        JPanel eyebrow = Widgets.flow(8, 0);
        eyebrow.add(Widgets.pill("OCTO LOADER", Style.CYAN));
        text.add(eyebrow);
        text.add(gap(14));
        heroTitle.setFont(Style.display(40f));
        heroTitle.setForeground(Style.TEXT);
        text.add(heroTitle);
        text.add(gap(4));
        text.add(heroDetail);
        text.add(gap(26));
        JPanel actions = Widgets.flow(12, 10);
        actions.add(launch);
        actions.add(versionButton);
        text.add(actions);
        text.add(gap(12));
        text.add(launchHint);
        progressBar.setVisible(false);
        JPanel barHolder = Widgets.clear(new BorderLayout());
        barHolder.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        barHolder.add(progressBar, BorderLayout.WEST);
        text.add(barHolder);
        hero.add(text, BorderLayout.CENTER);
        return hero;
    }

    /** The banner art: glows in the accent colours, a drift of blocks, and the mark. */
    private static final class Hero extends JPanel {
        private final Image mark = Mark.image(240);
        /** The creeper, drawn from the player's own Minecraft, once it is read. */
        private volatile Image mob;

        void mob(Image image) {
            mob = image;
            repaint();
        }

        Hero() {
            super(new BorderLayout());
            setOpaque(false);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(super.getPreferredSize().width, Math.max(292, super.getPreferredSize().height));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Style.smooth(graphics);
            int w = getWidth();
            int h = getHeight();
            g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h, 24, 24));
            g.setPaint(new GradientPaint(0, 0, new Color(0x1C, 0x13, 0x42), w, h, new Color(0x06, 0x1B, 0x30)));
            g.fillRect(0, 0, w, h);
            glow(g, w * 0.15f, h * 0.05f, Math.max(w, h) * 0.55f, Style.alpha(Style.VIOLET, 120));
            glow(g, w * 0.95f, h * 1.0f, Math.max(w, h) * 0.5f, Style.alpha(Style.CYAN, 95));

            // The art takes the right side, and steps back when the window
            // is narrow so it never sits under the text and buttons.
            int markSize = Math.min(220, h - 60);
            int markX = w - markSize - 46;
            boolean roomy = markX > 520;
            Random random = new Random(7);

            for (int i = 0; i < 20; i++) {
                float size = 12 + random.nextFloat() * 32;
                float x = w * 0.48f + random.nextFloat() * w * 0.52f;
                float y = random.nextFloat() * h;
                block(g, x, y, size, random.nextBoolean() ? Style.VIOLET : Style.CYAN,
                        (roomy ? 40 : 18) + random.nextInt(roomy ? 70 : 30));
            }

            Image creeper = mob;

            if (creeper != null) {
                // Big, standing on the banner's floor at the right.
                int mobHeight = h - 8;
                int mobWidth = creeper.getWidth(null) * mobHeight / creeper.getHeight(null);
                int mobX = w - mobWidth - 36;
                boolean fits = mobX > 500;
                int drawX = Math.max(mobX, w - mobWidth - 10);

                if (fits) {
                    glow(g, drawX + mobWidth / 2f, h * 0.55f, mobHeight * 0.75f, new Color(61, 220, 151, 70));
                }

                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, fits ? 1f : 0.22f));
                g.drawImage(creeper, drawX, h - mobHeight + 4, mobWidth, mobHeight, null);
            } else {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, roomy ? 1f : 0.16f));
                g.drawImage(mark, Math.max(markX, w - markSize - 20), (h - markSize) / 2, markSize, markSize, null);
            }

            g.setComposite(AlphaComposite.SrcOver);
            g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 130), w * 0.62f, 0, new Color(0, 0, 0, 0)));
            g.fillRect(0, 0, w, h);
            g.setClip(null);
            g.setColor(Style.alpha(Color.WHITE, 20));
            g.drawRoundRect(0, 0, w - 1, h - 1, 24, 24);
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

    /** The creeper for the banner, from the Minecraft the player has. */
    private void loadMob() {
        if (mobLoaded) {
            return;
        }

        mobLoaded = true;
        app.run(() -> {
            java.awt.image.BufferedImage texture = MobArt.texture(app.settings().gameDir(),
                    app.settings().selectedVersion);
            return texture == null ? null : MobArt.render(texture, 560);
        }, image -> {
            if (image != null) {
                heroArt.mob(image);
            }
        });
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
            JMenuItem item = new JMenuItem(version.id() + (version.withOcto() ? "    Octo installed" : ""));
            item.setFont(Style.font(Font.PLAIN, 13f));
            item.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 24));
            item.addActionListener(event -> {
                app.settings().selectedVersion = version.id();
                app.save();
                showVersion();
            });
            menu.add(item);
        });
        JMenuItem all = new JMenuItem("All versions...");
        all.setFont(Style.font(Font.BOLD, 13f));
        all.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 24));
        all.addActionListener(event -> app.go("Versions"));
        menu.add(all);
        menu.show(versionButton, 0, versionButton.getHeight() + 6);
    }

    /** The line under Launch: who plays, and how the game starts. */
    private void showLaunchHint() {
        if (preparing || running != null && running.process().isAlive()) {
            return;
        }

        if (!Play.launchesItself(app.settings())) {
            launchHint.setText("Opens the Minecraft Launcher with Octo selected. Sign in and play there.");
        } else if (app.accounts().active() == null) {
            launchHint.setText("Sign in with Microsoft to play. Octo downloads Minecraft and starts it for you.");
        } else {
            launchHint.setText("Playing as " + app.accounts().active().name() + ". Minecraft starts with Octo and "
                    + "your mods.");
        }
    }

    private void launch() {
        if (preparing || running != null && running.process().isAlive()) {
            return;
        }

        if (!Play.launchesItself(app.settings())) {
            openOfficialLauncher();
            return;
        }

        if (app.accounts().active() == null) {
            SignInSheet.show(app, account -> launch());
            return;
        }

        preparing = true;
        launch.setEnabled(false);
        launch.setText("Getting ready...");
        progressBar.setVisible(true);
        progressBar.set(-1);
        launchHint.setText("Checking your sign-in...");
        GameFiles.Progress progress = (stage, done, total) -> javax.swing.SwingUtilities.invokeLater(() -> {
            if (!preparing) {
                return;
            }

            launchHint.setText(stage + (total > 0 ? "   " + DeleteSheet.bytes(done) + " of "
                    + DeleteSheet.bytes(total) : ""));
            progressBar.set(total > 0 ? done / (double) total : -1);
            launch.setText(total > 0 ? Math.round(done * 100.0 / total) + "%" : "Getting ready...");
        });
        app.run(() -> Play.launch(app.settings(), app.accounts(), app.settings().selectedVersion, progress),
                started -> {
                    preparing = false;
                    running = started;
                    progressBar.setVisible(false);
                    launch.setText("Playing");
                    launchHint.setText(started.withOcto() ? "Minecraft " + started.version() + " is running. Have fun."
                            : "Minecraft " + started.version() + " is running, without mods: Octo needs 1.13 or "
                                    + "newer.");
                    watch(started);
                }, failure -> {
                    preparing = false;
                    progressBar.setVisible(false);
                    launch.setEnabled(true);
                    launch.setText("Launch game");
                    showLaunchHint();
                });
    }

    /** Waits for the game to close, then gets Launch ready again and says if it crashed. */
    private void watch(Play.Running game) {
        long started = System.currentTimeMillis();
        Thread waiter = new Thread(() -> {
            int code;

            try {
                code = game.process().waitFor();
            } catch (InterruptedException e) {
                return;
            }

            int exit = code;
            javax.swing.SwingUtilities.invokeLater(() -> {
                running = null;
                launch.setEnabled(true);
                launch.setText("Launch game");
                showLaunchHint();

                if (exit != 0) {
                    app.toast("Minecraft closed with an error (code " + exit + ")"
                            + (System.currentTimeMillis() - started < 60_000 ? " while starting" : "")
                            + ". Its output is in " + SettingsPage.tidy(game.log()) + ".", Style.RED);
                }

                app.changed();
            });
        }, "octo-game-watch");
        waiter.setDaemon(true);
        waiter.start();
    }

    private void openOfficialLauncher() {
        launch.setEnabled(false);
        launch.setText("Getting ready...");
        app.run(() -> Play.prepareAndOpen(app.settings(), app.settings().selectedVersion, message -> { }), result -> {
            app.toast(result, Style.GREEN);
            app.changed();
        });

        javax.swing.Timer reset = new javax.swing.Timer(2500, event -> {
            launch.setEnabled(true);
            launch.setText("Launch game");
        });
        reset.setRepeats(false);
        reset.start();
    }

    /** A thin bar: how far, or a sweep while it is not known. */
    static final class ProgressBar extends JComponent {
        private double value = -1;
        private float sweep;
        private final javax.swing.Timer timer = new javax.swing.Timer(16, event -> {
            sweep = (sweep + 0.012f) % 1f;
            repaint();
        });

        ProgressBar() {
            setPreferredSize(new Dimension(360, 6));
        }

        void set(double fraction) {
            value = fraction;

            if (fraction < 0 && isShowing()) {
                timer.start();
            } else {
                timer.stop();
            }

            repaint();
        }

        @Override
        public void addNotify() {
            super.addNotify();

            if (value < 0) {
                timer.start();
            }
        }

        @Override
        public void removeNotify() {
            timer.stop();
            super.removeNotify();
        }

        @Override
        public void setVisible(boolean visible) {
            super.setVisible(visible);

            if (!visible) {
                timer.stop();
            } else if (value < 0) {
                timer.start();
            }
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Style.smooth(graphics);
            int w = getWidth();
            int h = getHeight();
            g.setColor(Style.alpha(Color.WHITE, 26));
            g.fillRoundRect(0, 0, w, h, h, h);
            g.setPaint(Style.accent(0, w));

            if (value < 0) {
                int part = w / 3;
                int x = (int) (sweep * (w + part)) - part;
                g.setClip(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h, h, h));
                g.fillRoundRect(x, 0, part, h, h, h);
            } else {
                g.fillRoundRect(0, 0, (int) Math.max(h, w * Math.min(1, value)), h, h, h);
            }

            g.dispose();
        }
    }

    // ------------------------------------------------------------- news

    static JComponent header(String title, String link, Runnable onLink) {
        JPanel row = Widgets.clear(new BorderLayout());
        row.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0));
        JLabel label = new JLabel(title);
        label.setFont(Style.display(18f));
        label.setForeground(Style.TEXT);
        row.add(label, BorderLayout.WEST);

        if (link != null) {
            Widgets.Button more = new Widgets.Button(link, Icons.of(Icons.Glyph.CHEVRON_RIGHT, 13, Style.MUTED),
                    Widgets.Kind.GHOST, 8);
            more.setHorizontalTextPosition(javax.swing.SwingConstants.LEFT);
            more.setForeground(Style.MUTED);
            more.setFont(Style.font(Font.BOLD, 12.5f));
            more.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 8));
            more.addActionListener(event -> onLink.run());
            row.add(more, BorderLayout.EAST);
        }

        return row;
    }

    private void loadNews() {
        app.run(() -> News.latest(3), items -> {
            news.removeAll();

            for (int i = 0; i < items.size(); i++) {
                news.add(newsCard(items.get(i), i));
            }

            if (items.isEmpty()) {
                news.add(ModsPage.empty("No news yet", "Releases appear here once there is a connection."));
            }

            news.revalidate();
            news.repaint();
        }, failure -> {
            news.removeAll();
            news.add(ModsPage.empty("News could not be loaded", "Check your connection; it is read again the next "
                    + "time Octo Launcher opens."));
            news.revalidate();
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
            public Dimension getPreferredSize() {
                return new Dimension(10, 84);
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Style.smooth(graphics);
                int w = getWidth();
                int h = getHeight();
                Path2D shape = new Path2D.Float();
                shape.append(new java.awt.geom.RoundRectangle2D.Float(0, 0, w, h + 20, 16, 16), false);
                g.setClip(new java.awt.geom.Rectangle2D.Float(0, 0, w, h));
                g.clip(shape);
                g.setPaint(new GradientPaint(0, 0, palette[1], w, h, Style.mix(palette[0], Color.BLACK, 0.15f)));
                g.fillRect(0, 0, w, h);
                g.setColor(Style.alpha(Color.WHITE, 16));

                for (int i = -h; i < w; i += 16) {
                    g.drawLine(i, h, i + h, 0);
                }

                Image mark = Mark.image(64);
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.9f));
                g.drawImage(mark, w - 76, (h - 64) / 2, 64, 64, null);
                g.setComposite(AlphaComposite.SrcOver);
                g.setFont(Style.strong(11.5f));
                g.setColor(Style.alpha(Color.WHITE, 230));
                g.drawString(index == 0 ? "NEW RELEASE" : "RELEASE", 16, 26);
                g.dispose();
            }
        };
        art.setOpaque(false);
        card.add(art, BorderLayout.NORTH);

        JPanel body = Widgets.stack(5);
        body.setBorder(BorderFactory.createEmptyBorder(14, 16, 16, 16));
        body.add(new Text(item.title(), Style.font(Font.BOLD, 14f), Style.TEXT, 2));
        body.add(Widgets.line(DATE.format(item.published()), Style.font(Font.PLAIN, 12f), Style.FAINT));
        body.add(Widgets.paragraph(item.summary(), 12.5f, Style.MUTED, 2));
        card.add(body, BorderLayout.CENTER);
        return card;
    }

    // ----------------------------------------------------------- popular

    private void loadPopular() {
        app.run(() -> Modrinth.search("", null, null, "downloads", 0, 6), page -> {
            popular.removeAll();

            for (Modrinth.Project project : page.projects()) {
                popular.add(popularCard(project));
            }

            popular.revalidate();
            popular.repaint();
        }, failure -> {
            popular.removeAll();
            popular.add(ModsPage.empty("Modrinth could not be reached", "Popular mods appear here when there is a "
                    + "connection."));
            popular.revalidate();
        });
    }

    private JComponent popularCard(Modrinth.Project project) {
        Widgets.Card card = new Widgets.Card(Style.PANEL, 16, true).hoverable(() -> app.showMod(project));
        card.setLayout(new BorderLayout(0, 12));
        card.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JLabel icon = new JLabel();
        Images.load(project.iconUrl(), project.title(), project.color() == 0 ? null : new Color(project.color()),
                48, 12, image -> icon.setIcon(new javax.swing.ImageIcon(image)));
        JPanel top = Widgets.clear(new BorderLayout(12, 0));
        top.add(icon, BorderLayout.WEST);
        JPanel name = Widgets.stack(3);
        name.add(Widgets.line(project.title(), Style.font(Font.BOLD, 14f), Style.TEXT));
        name.add(Widgets.line(Widgets.count(project.downloads()) + " downloads", Style.font(Font.PLAIN, 12f),
                Style.FAINT));
        top.add(name, BorderLayout.CENTER);
        card.add(top, BorderLayout.NORTH);
        card.add(Widgets.paragraph(project.description(), 12.5f, Style.MUTED, 2), BorderLayout.CENTER);
        card.add(ModsPage.installButton(app, project), BorderLayout.SOUTH);
        return card;
    }

    // -------------------------------------------------------------- side

    private JComponent side() {
        JPanel side = Widgets.stack(16);
        side.setBorder(BorderFactory.createEmptyBorder(0, 0, 24, 2));

        Widgets.Card worldCard = Widgets.card();
        worldCard.setLayout(new BorderLayout(0, 10));
        worldCard.add(header("Your worlds", "Manage", () -> app.go("Worlds")), BorderLayout.NORTH);
        worldCard.add(worlds, BorderLayout.CENTER);
        side.add(worldCard);

        Widgets.Card modsCard = Widgets.card();
        modsCard.setLayout(new BorderLayout(0, 10));
        modsCard.add(header("Mods", "Manage", () -> app.go("Mods")), BorderLayout.NORTH);
        modsCard.add(modsBody, BorderLayout.CENTER);
        side.add(modsCard);

        Widgets.Card octoCard = Widgets.card();
        octoCard.setLayout(new BorderLayout(0, 10));
        octoCard.add(header("Octo", null, null), BorderLayout.NORTH);
        octoCard.add(octoBody, BorderLayout.CENTER);
        side.add(octoCard);
        return side;
    }

    private void showWorlds(List<Worlds.World> list) {
        worlds.removeAll();

        if (list.isEmpty()) {
            worlds.add(Widgets.paragraph("Worlds you play appear here, with their backups.", 12.5f, Style.MUTED, 0));
        }

        for (Worlds.World world : list.stream().limit(5).toList()) {
            worlds.add(worldRow(world));
        }

        worlds.revalidate();
        worlds.repaint();
    }

    private void showMods(List<ModLibrary.InstalledMod> mods) {
        modsBody.removeAll();
        long on = mods.stream().filter(ModLibrary.InstalledMod::enabled).count();
        JPanel figures = Widgets.clear(new java.awt.GridLayout(1, 2, 10, 0));
        figures.add(figure(Long.toString(on), "on"));
        figures.add(figure(Long.toString(mods.size() - on), "off"));
        modsBody.add(figures);
        Map<String, Integer> byLoader = new TreeMap<>();
        mods.forEach(mod -> {
            if (mod.loader() != null && !mod.loader().isBlank()) {
                byLoader.merge(mod.loader(), 1, Integer::sum);
            }
        });

        if (!byLoader.isEmpty()) {
            JPanel loaders = Widgets.flow(6, 6);
            byLoader.forEach((loader, count) -> loaders.add(Widgets.pill(loader + "  " + count,
                    ModsPage.loaderColour(loader))));
            modsBody.add(loaders);
        }

        modsBody.revalidate();
        modsBody.repaint();
    }

    private void showOcto(List<Worlds.World> list) {
        octoBody.removeAll();
        String loader = Updates.loaderTagNow();
        octoBody.add(status(Icons.Glyph.VERSIONS, "Octo Loader", loader == null ? "Built in" : News.title(loader)));
        octoBody.add(status(Icons.Glyph.WORLDS, "World guard", list.stream().anyMatch(Worlds.World::needsAttention)
                ? "Protecting a world" : "All worlds whole"));
        int backups = list.stream().mapToInt(Worlds.World::backups).sum();
        octoBody.add(status(Icons.Glyph.BACKUP, "Backups", backups + " across " + list.size() + " world"
                + (list.size() == 1 ? "" : "s")));
        octoBody.add(status(Icons.Glyph.UPDATE, "Launcher", Launcher.build().equals("dev") ? "Development build"
                : News.title(Launcher.build())));
        octoBody.revalidate();
        octoBody.repaint();
    }

    private static JComponent figure(String value, String label) {
        Widgets.Card tile = new Widgets.Card(Style.RAISED, 12, false);
        tile.setLayout(new BorderLayout());
        tile.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JLabel number = new JLabel(value);
        number.setFont(Style.display(22f));
        number.setForeground(Style.TEXT);
        tile.add(number, BorderLayout.CENTER);
        tile.add(Widgets.text(label, 12f, Font.PLAIN, Style.FAINT), BorderLayout.SOUTH);
        return tile;
    }

    private static JComponent status(Icons.Glyph glyph, String title, String value) {
        JPanel row = Widgets.clear(new BorderLayout(10, 0));
        row.add(new JLabel(Icons.of(glyph, 16, Style.MUTED)), BorderLayout.WEST);
        row.add(Widgets.line(title, Style.font(Font.PLAIN, 12.5f), Style.MUTED), BorderLayout.CENTER);
        JLabel right = Widgets.text(value, 12.5f, Font.BOLD, Style.TEXT);
        row.add(right, BorderLayout.EAST);
        return row;
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
        row.setBorder(BorderFactory.createEmptyBorder(6, 4, 6, 4));
        row.add(Widgets.picture(worldIcon(world, 38)), BorderLayout.WEST);
        JPanel text = Widgets.stack(2);
        text.add(Widgets.line(world.name(), Style.font(Font.BOLD, 13.5f), Style.TEXT));
        text.add(Widgets.line(ago(world.lastPlayed()) + "  ·  " + world.backups() + " backups",
                Style.font(Font.PLAIN, 12f), Style.FAINT));
        row.add(text, BorderLayout.CENTER);

        if (world.needsAttention()) {
            JLabel alert = new JLabel(Icons.of(Icons.Glyph.ALERT, 16, Style.AMBER));
            alert.setToolTipText("Missing content from " + world.missingMods() + ": Octo is protecting it");
            row.add(alert, BorderLayout.EAST);
        }

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

    /** Backups of every world, for the status line. */
    static int backupsOf(Worlds.World world) {
        return WorldBackups.list(WorldBackups.folderFor(world.folder())).size();
    }
}
