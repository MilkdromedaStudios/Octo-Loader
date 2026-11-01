package studios.milkdromeda.octo.launcher.ui;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;

import studios.milkdromeda.octo.installer.Mark;
import studios.milkdromeda.octo.installer.Theme;
import studios.milkdromeda.octo.launcher.Launcher;
import studios.milkdromeda.octo.launcher.LauncherSettings;
import studios.milkdromeda.octo.launcher.Updates;

/**
 * The Octo client window: its own title bar, a rail of pages down the left,
 * and the page. Frameless, so it looks the same on Windows, macOS and Linux;
 * it moves by its title bar and resizes from any edge.
 */
public final class ClientWindow implements App {
    private static final int EDGE = 6;

    private final JFrame frame = new JFrame("Octo Launcher");
    private final LauncherSettings settings = LauncherSettings.load();
    private final CardLayout cards = new CardLayout();
    private final JPanel pages = Widgets.clear(cards);
    private final Map<String, Page> byName = new LinkedHashMap<>();
    private final Map<String, RailButton> rail = new LinkedHashMap<>();
    private final JPanel toasts = Widgets.column();
    private final Widgets.Button updateChip = Widgets.button("Update ready", Icons.Glyph.UPDATE, Widgets.Kind.PRIMARY);
    private Updates.Release pendingUpdate;
    private final studios.milkdromeda.octo.launcher.auth.Accounts accounts =
            studios.milkdromeda.octo.launcher.Play.accounts();
    private final JPanel accountHolder = Widgets.row(0);

    public static void show() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            Theme.applyToDialogs();
            UIManager.put("ToolTip.background", Style.RAISED);
            UIManager.put("ToolTip.foreground", Style.TEXT);
            UIManager.put("ToolTip.border", BorderFactory.createLineBorder(Style.LINE));
            UIManager.put("PopupMenu.background", Style.RAISED);
            UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(Style.LINE));
            UIManager.put("MenuItem.background", Style.RAISED);
            UIManager.put("MenuItem.foreground", Style.TEXT);
            UIManager.put("MenuItem.selectionBackground", Style.HOVER);
            UIManager.put("MenuItem.selectionForeground", Style.TEXT);
            UIManager.put("MenuItem.font", Style.font(Font.PLAIN, 13f));
            UIManager.put("Separator.foreground", Style.LINE);
            UIManager.put("Separator.background", Style.RAISED);
            UIManager.put("Separator.highlight", Style.RAISED);
            UIManager.put("Separator.shadow", Style.LINE);
            ToolTipManager.sharedInstance().setInitialDelay(350);
        } catch (Exception e) {
            // The window paints itself.
        }

        SwingUtilities.invokeLater(() -> new ClientWindow().open());
    }

    private void open() {
        studios.milkdromeda.octo.launcher.mods.Modrinth.userAgent(launcherUserAgent());
        studios.milkdromeda.octo.launcher.game.Net.userAgent(launcherUserAgent());
        frame.setUndecorated(true);
        frame.setIconImages(Mark.icons());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(1080, 680));
        frame.setSize(new Dimension(1260, 780));

        // -Docto.launcher.size=1080x680: a size to open at, for checking layouts.
        String size = System.getProperty("octo.launcher.size");

        if (size != null && size.matches("\\d+x\\d+")) {
            String[] parts = size.split("x");
            frame.setSize(new Dimension(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
        }
        frame.setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Style.smooth(graphics);
                g.setColor(Style.WINDOW);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Style.LINE);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g.dispose();
            }
        };
        root.setBorder(BorderFactory.createEmptyBorder(1, 1, 1, 1));
        root.add(titleBar(), BorderLayout.NORTH);

        JPanel body = Widgets.clear(new BorderLayout());
        body.add(rail(), BorderLayout.WEST);
        pages.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        body.add(pages, BorderLayout.CENTER);
        root.add(body, BorderLayout.CENTER);

        add("Home", new HomePage(this));
        add("Mods", new ModsPage(this));
        add("Worlds", new WorldsPage(this));
        add("Skins", new SkinsPage(this));
        add("Versions", new VersionsPage(this));
        add("Settings", new SettingsPage(this));

        JLayeredPane layers = frame.getLayeredPane();
        toasts.setSize(380, 300);
        layers.add(toasts, JLayeredPane.POPUP_LAYER);
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                placeToasts();
            }
        });

        frame.setContentPane(root);
        resizable(root);
        frame.setVisible(true);
        placeToasts();
        go(System.getProperty("octo.launcher.page", "Home"));
        changed();

        // -Docto.launcher.mod=create: opens that mod's page, for checking it.
        String mod = System.getProperty("octo.launcher.mod");

        if (mod != null && !mod.isBlank()) {
            run(() -> studios.milkdromeda.octo.launcher.mods.Modrinth.search(mod, null, null, "relevance", 0, 1),
                    page -> page.projects().stream().findFirst().ifPresent(this::showMod));
        }

        // -Docto.launcher.open=sign-in or delete-files: opens that sheet, for checking it.
        switch (System.getProperty("octo.launcher.open", "")) {
            case "sign-in" -> SignInSheet.show(this, account -> { });
            case "delete-files" -> DeleteSheet.show(this);
            default -> {
                // Nothing.
            }
        }

        if (settings.checkForUpdates) {
            checkForUpdates();
        }
    }

    private void add(String name, Page page) {
        byName.put(name, page);
        pages.add(page.view(), name);
    }

    // ---------------------------------------------------------- title bar

    private JComponent titleBar() {
        JPanel bar = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Style.smooth(graphics);
                g.setColor(Style.RAIL);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Style.LINE);
                g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
                g.dispose();
            }
        };
        bar.setPreferredSize(new Dimension(10, 44));

        JPanel brand = Widgets.row(8);
        brand.setBorder(BorderFactory.createEmptyBorder(9, 18, 0, 0));
        brand.add(new Mark(24));
        JLabel name = Widgets.text("<html><b>Octo</b> <span style='color:" + Style.hex(Style.FAINT)
                + "'>Launcher</span></html>", 14f, Font.PLAIN, Style.TEXT);
        brand.add(name);
        bar.add(brand, BorderLayout.WEST);

        JPanel right = Widgets.row(2);
        right.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 6));
        updateChip.setVisible(false);
        updateChip.setFont(Style.font(Font.BOLD, 12f));
        updateChip.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        updateChip.addActionListener(event -> applyUpdate());
        right.add(updateChip);
        right.add(Box.createHorizontalStrut(10));
        right.add(accountHolder);
        right.add(Box.createHorizontalStrut(8));
        right.add(windowButton(Icons.Glyph.MINIMIZE, () -> frame.setState(Frame.ICONIFIED), false));
        right.add(windowButton(Icons.Glyph.MAXIMIZE, this::toggleMaximized, false));
        right.add(windowButton(Icons.Glyph.CLOSE, () -> {
            frame.dispose();
            System.exit(0);
        }, true));
        bar.add(right, BorderLayout.EAST);

        // Moving the window by its title bar; a double click maximizes.
        MouseAdapter drag = new MouseAdapter() {
            private Point grab;

            @Override
            public void mousePressed(MouseEvent event) {
                grab = event.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (grab == null) {
                    return;
                }

                if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) {
                    frame.setExtendedState(Frame.NORMAL);
                    grab = new Point(frame.getWidth() / 2, grab.y);
                }

                Point screen = event.getLocationOnScreen();
                frame.setLocation(screen.x - grab.x, screen.y - grab.y);
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    toggleMaximized();
                }
            }
        };
        bar.addMouseListener(drag);
        bar.addMouseMotionListener(drag);
        brand.addMouseListener(drag);
        brand.addMouseMotionListener(drag);
        return bar;
    }

    private JComponent windowButton(Icons.Glyph glyph, Runnable action, boolean close) {
        JLabel button = new JLabel(Icons.of(glyph, 16, Style.MUTED)) {
            boolean hover;

            {
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent event) {
                        hover = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent event) {
                        hover = false;
                        repaint();
                    }

                    @Override
                    public void mouseClicked(MouseEvent event) {
                        action.run();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                if (hover) {
                    Graphics2D g = Style.smooth(graphics);
                    g.setColor(close ? Style.RED : Style.HOVER);
                    g.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                    g.dispose();
                }

                super.paintComponent(graphics);
            }
        };
        button.setPreferredSize(new Dimension(40, 32));
        button.setHorizontalAlignment(SwingConstants.CENTER);
        return button;
    }

    private void toggleMaximized() {
        if ((frame.getExtendedState() & Frame.MAXIMIZED_BOTH) != 0) {
            frame.setExtendedState(Frame.NORMAL);
        } else {
            // The usable screen, not the whole one: the task bar stays visible.
            Rectangle usable = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            frame.setMaximizedBounds(usable);
            frame.setExtendedState(Frame.MAXIMIZED_BOTH);
        }
    }

    /** Resizing from the window's edges, which a frameless window does not do by itself. */
    private void resizable(JComponent root) {
        MouseAdapter resize = new MouseAdapter() {
            private int edge;
            private Point start;
            private Rectangle bounds;

            private int edgeAt(Point point) {
                int w = root.getWidth();
                int h = root.getHeight();
                int edgeFlags = 0;
                edgeFlags |= point.x < EDGE ? 1 : 0;
                edgeFlags |= point.x > w - EDGE ? 2 : 0;
                edgeFlags |= point.y < EDGE ? 4 : 0;
                edgeFlags |= point.y > h - EDGE ? 8 : 0;
                return edgeFlags;
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                int at = edgeAt(event.getPoint());
                int cursor = switch (at) {
                    case 1 -> Cursor.W_RESIZE_CURSOR;
                    case 2 -> Cursor.E_RESIZE_CURSOR;
                    case 4 -> Cursor.N_RESIZE_CURSOR;
                    case 8 -> Cursor.S_RESIZE_CURSOR;
                    case 5 -> Cursor.NW_RESIZE_CURSOR;
                    case 6 -> Cursor.NE_RESIZE_CURSOR;
                    case 9 -> Cursor.SW_RESIZE_CURSOR;
                    case 10 -> Cursor.SE_RESIZE_CURSOR;
                    default -> Cursor.DEFAULT_CURSOR;
                };
                root.setCursor(Cursor.getPredefinedCursor(cursor));
            }

            @Override
            public void mousePressed(MouseEvent event) {
                edge = edgeAt(event.getPoint());
                start = event.getLocationOnScreen();
                bounds = frame.getBounds();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (edge == 0 || start == null) {
                    return;
                }

                Point now = event.getLocationOnScreen();
                int dx = now.x - start.x;
                int dy = now.y - start.y;
                Rectangle next = new Rectangle(bounds);
                Dimension min = frame.getMinimumSize();

                if ((edge & 1) != 0) {
                    int width = Math.max(min.width, bounds.width - dx);
                    next.x = bounds.x + bounds.width - width;
                    next.width = width;
                }

                if ((edge & 2) != 0) {
                    next.width = Math.max(min.width, bounds.width + dx);
                }

                if ((edge & 4) != 0) {
                    int height = Math.max(min.height, bounds.height - dy);
                    next.y = bounds.y + bounds.height - height;
                    next.height = height;
                }

                if ((edge & 8) != 0) {
                    next.height = Math.max(min.height, bounds.height + dy);
                }

                frame.setBounds(next);
                frame.revalidate();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                edge = 0;
            }
        };
        root.setBorder(BorderFactory.createEmptyBorder(EDGE / 2, EDGE / 2, EDGE / 2, EDGE / 2));
        root.addMouseListener(resize);
        root.addMouseMotionListener(resize);
    }

    // --------------------------------------------------------------- rail

    private JComponent rail() {
        JPanel column = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Style.smooth(graphics);
                g.setColor(Style.RAIL);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Style.LINE);
                g.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());
                g.dispose();
            }
        };
        column.setLayout(new javax.swing.BoxLayout(column, javax.swing.BoxLayout.Y_AXIS));
        column.setPreferredSize(new Dimension(84, 10));
        column.setBorder(BorderFactory.createEmptyBorder(14, 0, 14, 0));

        Object[][] items = { { "Home", Icons.Glyph.HOME }, { "Mods", Icons.Glyph.MODS },
                { "Worlds", Icons.Glyph.WORLDS }, { "Skins", Icons.Glyph.SKIN }, { "Versions", Icons.Glyph.VERSIONS } };

        for (Object[] item : items) {
            RailButton button = new RailButton((String) item[0], (Icons.Glyph) item[1]);
            rail.put((String) item[0], button);
            column.add(button);
            column.add(Box.createVerticalStrut(6));
        }

        column.add(Box.createVerticalGlue());
        RailButton settingsButton = new RailButton("Settings", Icons.Glyph.SETTINGS);
        rail.put("Settings", settingsButton);
        column.add(settingsButton);
        column.add(Box.createVerticalStrut(8));
        JLabel version = Widgets.text("v" + studios.milkdromeda.octo.installer.Installer.VERSION, 10.5f, Font.PLAIN,
                Style.FAINT);
        version.setAlignmentX(Component.CENTER_ALIGNMENT);
        version.setToolTipText(Launcher.build());
        column.add(version);
        return column;
    }

    /** An icon and its name, lit when its page is showing. */
    private final class RailButton extends JComponent {
        private final String name;
        private final Icons.Glyph glyph;
        private boolean hover;
        private boolean selected;

        RailButton(String name, Icons.Glyph glyph) {
            this.name = name;
            this.glyph = glyph;
            setPreferredSize(new Dimension(84, 62));
            setMaximumSize(new Dimension(84, 62));
            setAlignmentX(Component.CENTER_ALIGNMENT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hover = false;
                    repaint();
                }

                @Override
                public void mouseClicked(MouseEvent event) {
                    go(name);
                }
            });
        }

        void selected(boolean value) {
            selected = value;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Style.smooth(graphics);
            int boxX = 14;
            int boxW = getWidth() - 28;

            if (selected) {
                g.setColor(Style.alpha(Style.VIOLET, 40));
                g.fillRoundRect(boxX, 2, boxW, 58, 14, 14);
                g.setPaint(new GradientPaint(0, 12, Style.VIOLET, 0, 50, Style.CYAN));
                g.fillRoundRect(0, 14, 4, 34, 4, 4);
            } else if (hover) {
                g.setColor(Style.HOVER);
                g.fillRoundRect(boxX, 2, boxW, 58, 14, 14);
            }

            Color colour = selected ? Style.TEXT : hover ? Style.TEXT : Style.MUTED;
            Icons.of(glyph, 22, colour).paintIcon(this, g, (getWidth() - 22) / 2, 10);
            g.setFont(Style.font(Font.BOLD, 10.5f));
            g.setColor(colour);
            int width = g.getFontMetrics().stringWidth(name);
            g.drawString(name, (getWidth() - width) / 2, 50);
            g.dispose();
        }
    }

    // ---------------------------------------------------------------- App

    @Override
    public LauncherSettings settings() {
        return settings;
    }

    @Override
    public JFrame frame() {
        return frame;
    }

    @Override
    public void go(String page) {
        if (!byName.containsKey(page)) {
            page = "Home";
        }

        cards.show(pages, page);
        String shown = page;
        rail.forEach((name, button) -> button.selected(name.equals(shown)));
    }

    @Override
    public void changed() {
        showAccount();
        byName.values().forEach(Page::refresh);
    }

    @Override
    public studios.milkdromeda.octo.launcher.auth.Accounts accounts() {
        return accounts;
    }

    /** The account in the title bar: its face and name, or a button to sign in. */
    private void showAccount() {
        accountHolder.removeAll();
        studios.milkdromeda.octo.launcher.auth.Account active = accounts.active();

        if (active == null) {
            if (studios.milkdromeda.octo.launcher.Play.launchesItself(settings)) {
                Widgets.Button signIn = Widgets.button("Sign in", Icons.Glyph.USER, Widgets.Kind.SECONDARY);
                signIn.setFont(Style.font(Font.BOLD, 12.5f));
                signIn.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 14));
                signIn.addActionListener(event -> SignInSheet.show(this, account -> { }));
                accountHolder.add(signIn);
            }
        } else {
            Widgets.Button chip = new Widgets.Button(active.name(), null, Widgets.Kind.GHOST, 10);
            chip.setFont(Style.font(Font.BOLD, 13f));
            chip.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 10));
            Heads.load(active, 24, 6, image -> chip.setIcon(new javax.swing.ImageIcon(image)));
            chip.addActionListener(event -> accountMenu(chip));
            accountHolder.add(chip);
        }

        accountHolder.revalidate();
        accountHolder.repaint();
    }

    private void accountMenu(JComponent under) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        menu.setBorder(BorderFactory.createLineBorder(Style.LINE));
        studios.milkdromeda.octo.launcher.auth.Account active = accounts.active();

        for (studios.milkdromeda.octo.launcher.auth.Account account : accounts.all()) {
            boolean current = active != null && account.id().equals(active.id());
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(account.name() + (current ? "    Playing" : ""));
            item.setFont(Style.font(current ? Font.BOLD : Font.PLAIN, 13f));
            item.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 24));
            Heads.load(account, 20, 5, image -> item.setIcon(new javax.swing.ImageIcon(image)));
            item.addActionListener(event -> run(() -> {
                accounts.use(account.id());
                return account;
            }, used -> changed()));
            menu.add(item);
        }

        menu.addSeparator();
        javax.swing.JMenuItem add = new javax.swing.JMenuItem("Add another account", Icons.of(Icons.Glyph.PLUS, 16,
                Style.MUTED));
        add.setFont(Style.font(Font.PLAIN, 13f));
        add.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 24));
        add.addActionListener(event -> SignInSheet.show(this, account -> { }));
        menu.add(add);

        if (active != null) {
            javax.swing.JMenuItem out = new javax.swing.JMenuItem("Sign out " + active.name(),
                    Icons.of(Icons.Glyph.SIGN_OUT, 16, Style.RED));
            out.setFont(Style.font(Font.PLAIN, 13f));
            out.setForeground(Style.RED);
            out.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 24));
            out.addActionListener(event -> signOut(active));
            menu.add(out);
        }

        menu.show(under, under.getWidth() - menu.getPreferredSize().width, under.getHeight() + 6);
    }

    @Override
    public void signOut(studios.milkdromeda.octo.launcher.auth.Account account) {
        run(() -> {
            accounts.remove(account.id());
            return account;
        }, gone -> {
            toast("Signed out " + gone.name() + ". Their sign-in is removed from this computer.", Style.MUTED);
            changed();
        });
    }

    @Override
    public void save() {
        try {
            settings.save();
        } catch (Exception e) {
            toast("Settings could not be saved: " + e.getMessage(), Style.RED);
        }
    }

    @Override
    public <T> void run(Callable<T> work, Consumer<T> done) {
        run(work, done, failure -> { });
    }

    @Override
    public <T> void run(Callable<T> work, Consumer<T> done, Consumer<Throwable> failed) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override
            protected void done() {
                try {
                    done.accept(get());
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    toast(cause.getMessage() == null ? cause.toString() : cause.getMessage(), Style.RED);
                    failed.accept(cause);
                }
            }
        }.execute();
    }

    @Override
    public void toast(String message, Color colour) {
        Toast toast = new Toast(message, colour);
        toasts.add(toast, 0);
        toasts.add(Box.createVerticalStrut(8), 1);
        placeToasts();
        Timer leave = new Timer(4800, event -> {
            toasts.remove(toast);

            if (toasts.getComponentCount() > 0 && !(toasts.getComponent(0) instanceof Toast)) {
                toasts.remove(0);
            }

            placeToasts();
        });
        leave.setRepeats(false);
        leave.start();
    }

    private void placeToasts() {
        Dimension preferred = toasts.getPreferredSize();
        toasts.setBounds(frame.getWidth() - 400, frame.getHeight() - preferred.height - 22, 380, preferred.height);
        toasts.revalidate();
        toasts.repaint();
    }

    /** A message in the corner, edged in its colour. */
    private static final class Toast extends JPanel {
        private final Color colour;

        Toast(String message, Color colour) {
            super(new BorderLayout(10, 0));
            this.colour = colour;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 14));
            // Wrapped to the toast's own width, a long path on a line of its own.
            add(new Text(message, Style.font(Font.PLAIN, 13f), Style.TEXT, 0, 340), BorderLayout.CENTER);
            setAlignmentX(Component.RIGHT_ALIGNMENT);
            setMaximumSize(new Dimension(380, 200));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Style.smooth(graphics);
            g.setColor(new Color(0, 0, 0, 90));
            g.fillRoundRect(2, 4, getWidth() - 2, getHeight() - 2, 14, 14);
            g.setColor(Style.RAISED);
            g.fillRoundRect(0, 0, getWidth() - 2, getHeight() - 4, 14, 14);
            g.setColor(Style.LINE);
            g.drawRoundRect(0, 0, getWidth() - 3, getHeight() - 5, 14, 14);
            g.setColor(colour);
            g.setComposite(AlphaComposite.SrcOver);
            g.fillRoundRect(0, 10, 4, getHeight() - 24, 4, 4);
            g.dispose();
        }
    }

    // ------------------------------------------------------------ updates

    private void checkForUpdates() {
        run(Updates::latest, release -> {
            boolean appNewer = Updates.isNewer(release) && release.launcherUrl() != null;
            boolean loaderNewer = release.loaderUrl() != null && !release.tag().equals(Updates.loaderTagNow())
                    && !Launcher.build().equals(release.tag());

            if (loaderNewer && settings.updateLoaderAutomatically) {
                run(() -> {
                    Updates.downloadLoader(release);
                    return Updates.updateInstallations(settings.gameDir(), message -> { });
                }, versions -> {
                    if (!versions.isEmpty()) {
                        toast("Octo Loader updated to " + studios.milkdromeda.octo.launcher.News.title(release.name()) + " for " + String.join(", ", versions)
                                + ".", Style.GREEN);
                    }
                });
            }

            if (appNewer) {
                pendingUpdate = release;
                updateChip.setText("Update to " + studios.milkdromeda.octo.launcher.News.title(release.name()));
                updateChip.setVisible(true);
            }
        });
    }

    private void applyUpdate() {
        Updates.Release release = pendingUpdate;

        if (release == null) {
            return;
        }

        toast("Downloading " + studios.milkdromeda.octo.launcher.News.title(release.name()) + "...", Style.MUTED);
        run(() -> Updates.stageLauncher(release), staged -> {
            try {
                Updates.restartInto(staged);
                frame.dispose();
                System.exit(0);
            } catch (Exception e) {
                toast("The update was downloaded but could not be put in place: " + e.getMessage(), Style.RED);
            }
        });
    }

    private static String launcherUserAgent() {
        return "MilkdromedaStudios/Octo-Loader/" + studios.milkdromeda.octo.installer.Installer.VERSION
                + " (Octo Launcher; https://github.com/MilkdromedaStudios/Octo-Loader)";
    }
}
