package studios.milkdromeda.octo.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.filechooser.FileNameExtensionFilter;

import studios.milkdromeda.octo.launcher.Play;
import studios.milkdromeda.octo.launcher.SkinLibrary;
import studios.milkdromeda.octo.launcher.auth.Account;
import studios.milkdromeda.octo.launcher.auth.MinecraftProfile;
import studios.milkdromeda.octo.launcher.game.Net;

/**
 * Skins: the player as others see them, turned with the mouse; a library of
 * skins to wear with one click; and the capes the account owns.
 */
final class SkinsPage implements App.Page {
    private static final Map<String, BufferedImage> TEXTURES = new ConcurrentHashMap<>();

    private final App app;
    private final JPanel view = Widgets.clear(new BorderLayout(22, 0));
    private final Preview preview = new Preview();
    private final JLabel previewName = Widgets.text(" ", 16f, Font.BOLD, Style.TEXT);
    private final JPanel previewActions = Widgets.flow(8, 8);
    private final JPanel skins = Widgets.grid(150, 4, 12);
    private final JPanel capes = Widgets.grid(120, 6, 12);
    private final JPanel capeNote = Widgets.stack(0);
    private final JPanel notice = Widgets.stack(0);
    private final SkinLibrary library = SkinLibrary.standard();

    private MinecraftProfile.State state;
    /** What is shown in the preview: a skin from the library, or the one worn now. */
    private SkinLibrary.Entry selected;
    private BufferedImage selectedTexture;
    private boolean selectedSlim;
    private BufferedImage capeTexture;
    private boolean busy;
    /** Why the profile could not be read, the last time it was tried; {@code null} when it was. */
    private String loadError;

    SkinsPage(App app) {
        this.app = app;
        view.setBorder(BorderFactory.createEmptyBorder(22, 24, 0, 24));

        // The preview, on the left.
        Widgets.Card stage = new Widgets.Card(Style.PANEL, 18, true);
        stage.setLayout(new BorderLayout(0, 12));
        stage.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
        stage.setPreferredSize(new Dimension(330, 10));
        stage.add(preview, BorderLayout.CENTER);
        JPanel under = Widgets.stack(10);
        previewName.setHorizontalAlignment(SwingConstants.CENTER);
        under.add(previewName);
        under.add(previewActions);
        stage.add(under, BorderLayout.SOUTH);
        JPanel left = Widgets.clear(new BorderLayout());
        left.setBorder(BorderFactory.createEmptyBorder(0, 0, 24, 0));
        left.add(stage, BorderLayout.CENTER);
        view.add(left, BorderLayout.WEST);

        // The library and the capes, on the right.
        JPanel column = Widgets.stack(0);
        column.setBorder(BorderFactory.createEmptyBorder(0, 0, 24, 4));
        column.add(Widgets.heading("Skins"));
        column.add(gap(4));
        column.add(Widgets.line("Your skin and cape, as other players see them. Drag the player to turn them.",
                Style.font(Font.PLAIN, 13f), Style.MUTED));
        column.add(gap(16));
        column.add(notice);
        column.add(HomePage.header("Your skins", "Add a skin", this::addMenu));
        column.add(skins);
        column.add(gap(26));
        column.add(HomePage.header("Capes", null, null));
        column.add(capeNote);
        column.add(capes);
        view.add(Widgets.page(column), BorderLayout.CENTER);
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
        Account account = app.accounts().active();
        notice.removeAll();

        if (account == null) {
            state = null;
            notice.add(signInNotice());
            notice.add(gap(20));
            showLibrary();
            showCapes();

            if (selectedTexture == null && !library.list().isEmpty()) {
                select(library.list().get(0));
            } else {
                showPreview();
            }

            return;
        }

        app.run(() -> {
            MinecraftProfile.State now = new MinecraftProfile().get(Play.session(app.settings(), app.accounts()));
            BufferedImage skin = now.skin() == null ? null : texture(now.skin().url());

            if (now.skin() != null && skin != null) {
                // What is worn now is kept, so it can be gone back to.
                byte[] png = png(skin);

                if (library.find(png) == null) {
                    library.add(account.name() + "'s skin", png, now.skin().slim());
                }
            }

            for (MinecraftProfile.Cape cape : now.capes()) {
                texture(cape.url());
            }

            return now;
        }, now -> {
            state = now;
            loadError = null;
            showLibrary();
            showCapes();

            if (selectedTexture == null || selected == null) {
                selectWorn();
            } else {
                capeTexture = activeCape();
                showPreview();
            }
        }, failure -> {
            loadError = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            showLibrary();
            showCapes();

            // Still something to look at: a saved skin.
            if (selectedTexture == null && !library.list().isEmpty()) {
                select(library.list().get(0));
            }
        });
    }

    // ---------------------------------------------------------- textures

    private static BufferedImage texture(String url) throws IOException {
        if (url == null) {
            return null;
        }

        BufferedImage known = TEXTURES.get(url);

        if (known != null) {
            return known;
        }

        try {
            var response = Net.client().send(Net.request(url).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + url);
            }

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(response.body()));

            if (image != null) {
                TEXTURES.put(url, image);
            }

            return image;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private static byte[] png(BufferedImage image) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private BufferedImage activeCape() {
        MinecraftProfile.Cape cape = state == null ? null : state.activeCape();
        return cape == null ? null : TEXTURES.get(cape.url());
    }

    // ----------------------------------------------------------- preview

    private void selectWorn() {
        if (state != null && state.skin() != null && TEXTURES.get(state.skin().url()) != null) {
            BufferedImage worn = TEXTURES.get(state.skin().url());

            try {
                selected = library.find(png(worn));
            } catch (IOException e) {
                selected = null;
            }

            selectedTexture = worn;
            selectedSlim = state.skin().slim();
        }

        capeTexture = activeCape();
        showPreview();
    }

    private void select(SkinLibrary.Entry entry) {
        try {
            selected = entry;
            selectedTexture = ImageIO.read(library.file(entry).toFile());
            selectedSlim = entry.slim();
            capeTexture = activeCape();
            showPreview();
            showLibrary();
        } catch (IOException e) {
            app.toast("That skin could not be read: " + e.getMessage(), Style.RED);
        }
    }

    private boolean wearing(SkinLibrary.Entry entry) {
        if (state == null || state.skin() == null || TEXTURES.get(state.skin().url()) == null) {
            return false;
        }

        try {
            SkinLibrary.Entry worn = library.find(png(TEXTURES.get(state.skin().url())));
            return worn != null && worn.id().equals(entry.id()) && state.skin().slim() == entry.slim();
        } catch (IOException e) {
            return false;
        }
    }

    private void showPreview() {
        preview.set(selectedTexture, selectedSlim, capeTexture);
        previewName.setText(selected != null ? selected.name()
                : app.accounts().active() != null ? app.accounts().active().name() : "No skin chosen");
        previewActions.removeAll();

        if (selectedTexture != null) {
            Widgets.Button classic = Widgets.button("Classic", null, selectedSlim ? Widgets.Kind.GHOST
                    : Widgets.Kind.SECONDARY);
            Widgets.Button slim = Widgets.button("Slim", null, selectedSlim ? Widgets.Kind.SECONDARY
                    : Widgets.Kind.GHOST);
            classic.setToolTipText("Arms four pixels wide");
            slim.setToolTipText("Arms three pixels wide");
            classic.addActionListener(event -> arms(false));
            slim.addActionListener(event -> arms(true));
            previewActions.add(classic);
            previewActions.add(slim);
        }

        boolean signedIn = app.accounts().active() != null && state != null;

        if (selected != null && signedIn) {
            boolean worn = wearing(selected);
            Widgets.Button wear = Widgets.button(worn ? "Wearing" : "Wear this skin", worn ? Icons.Glyph.CHECK
                    : Icons.Glyph.SKIN, worn ? Widgets.Kind.SECONDARY : Widgets.Kind.PRIMARY);
            wear.setEnabled(!worn && !busy);
            wear.addActionListener(event -> wear(selected));
            previewActions.add(wear);
        }

        previewActions.revalidate();
        previewActions.repaint();
    }

    private void arms(boolean slim) {
        selectedSlim = slim;

        if (selected != null) {
            try {
                selected = library.update(selected, selected.name(), slim);
            } catch (IOException e) {
                app.toast("Could not save that: " + e.getMessage(), Style.RED);
            }
        }

        showPreview();
        showLibrary();
    }

    /** The player, turned with the mouse. */
    private static final class Preview extends JComponent {
        private BufferedImage skin;
        private boolean slim;
        private BufferedImage cape;
        private double yaw = 25;
        private Image drawn;
        private int drawnHeight;
        private int dragFrom;
        private double yawFrom;

        Preview() {
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Drag to turn");
            MouseAdapter turn = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    dragFrom = event.getX();
                    yawFrom = yaw;
                }

                @Override
                public void mouseDragged(MouseEvent event) {
                    yaw = yawFrom - (event.getX() - dragFrom) * 0.8;
                    drawn = null;
                    repaint();
                }
            };
            addMouseListener(turn);
            addMouseMotionListener(turn);
        }

        void set(BufferedImage skin, boolean slim, BufferedImage cape) {
            this.skin = skin;
            this.slim = slim;
            this.cape = cape;
            drawn = null;
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(290, 360);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = Style.smooth(graphics);
            int w = getWidth();
            int h = getHeight();
            // A soft floor under the player.
            g.setColor(Style.alpha(Style.VIOLET, 40));
            g.fillOval(w / 2 - 70, h - 34, 140, 26);

            if (skin == null) {
                g.setColor(Style.FAINT);
                g.setFont(Style.font(Font.PLAIN, 13f));
                String text = "Add a skin to see it here";
                g.drawString(text, (w - g.getFontMetrics().stringWidth(text)) / 2, h / 2);
                g.dispose();
                return;
            }

            int height = Math.max(120, Math.min(h - 10, 420));

            if (drawn == null || drawnHeight != height || drawn.getWidth(null) > w - 24) {
                drawn = PlayerArt.render(skin, slim, cape, yaw, height);

                // Narrower when turned so the arms or the cape would reach the edges.
                if (drawn.getWidth(null) > w - 24) {
                    drawn = PlayerArt.render(skin, slim, cape, yaw, Math.max(120,
                            height * (w - 24) / drawn.getWidth(null)));
                }

                drawnHeight = height;
            }

            g.drawImage(drawn, (w - drawn.getWidth(null)) / 2, h - drawn.getHeight(null) - 4, null);
            g.dispose();
        }
    }

    // ----------------------------------------------------------- library

    private void showLibrary() {
        skins.removeAll();
        List<SkinLibrary.Entry> entries = library.list();

        for (SkinLibrary.Entry entry : entries) {
            skins.add(skinCard(entry));
        }

        if (entries.isEmpty()) {
            skins.add(ModsPage.empty("No skins yet", "Add one from a PNG file or from a player's name."));
        }

        skins.revalidate();
        skins.repaint();
    }

    private JComponent skinCard(SkinLibrary.Entry entry) {
        boolean isSelected = selected != null && selected.id().equals(entry.id());
        Widgets.Card card = new Widgets.Card(isSelected ? Style.RAISED : Style.PANEL, 14, true)
                .hoverable(() -> select(entry));
        card.setLayout(new BorderLayout(0, 8));
        card.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel picture = new JLabel();
        picture.setHorizontalAlignment(SwingConstants.CENTER);
        picture.setPreferredSize(new Dimension(10, 128));
        app.run(() -> PlayerArt.render(ImageIO.read(library.file(entry).toFile()), entry.slim(), null, 25, 128),
                image -> picture.setIcon(new javax.swing.ImageIcon(image)));
        card.add(picture, BorderLayout.CENTER);
        JPanel foot = Widgets.clear(new BorderLayout(6, 0));
        foot.add(Widgets.line(entry.name(), Style.font(Font.BOLD, 13f), Style.TEXT), BorderLayout.CENTER);

        if (wearing(entry)) {
            foot.add(Widgets.pill("Wearing", Style.GREEN), BorderLayout.EAST);
        } else {
            Widgets.Button remove = Widgets.iconButton(Icons.Glyph.TRASH, "Remove from your skins",
                    Widgets.Kind.DANGER);
            remove.setBorder(BorderFactory.createEmptyBorder(3, 3, 3, 3));
            remove.addActionListener(event -> remove(entry));
            foot.add(remove, BorderLayout.EAST);
        }

        card.add(foot, BorderLayout.SOUTH);
        return card;
    }

    private void addMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.setBorder(BorderFactory.createLineBorder(Style.LINE));
        JMenuItem fromFile = new JMenuItem("From a PNG file...", Icons.of(Icons.Glyph.FOLDER, 16, Style.MUTED));
        fromFile.addActionListener(event -> addFromFile());
        JMenuItem fromPlayer = new JMenuItem("From a player's name...", Icons.of(Icons.Glyph.USER, 16,
                Style.MUTED));
        fromPlayer.addActionListener(event -> addFromPlayer());

        for (JMenuItem item : new JMenuItem[] { fromFile, fromPlayer }) {
            item.setFont(Style.font(Font.PLAIN, 13f));
            item.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 24));
            menu.add(item);
        }

        menu.show(skins, Math.max(0, skins.getWidth() - menu.getPreferredSize().width), -6);
    }

    private void addFromFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Skins (PNG)", "png"));

        if (chooser.showOpenDialog(app.frame()) != JFileChooser.APPROVE_OPTION) {
            return;
        }

        java.nio.file.Path file = chooser.getSelectedFile().toPath();
        String name = file.getFileName().toString().replaceFirst("(?i)\\.png$", "").replace('_', ' ');
        app.run(() -> {
            byte[] png = Files.readAllBytes(file);
            BufferedImage image = SkinLibrary.validate(png);
            return library.add(name, png, looksSlim(image));
        }, entry -> {
            app.toast("Added " + entry.name() + " to your skins.", Style.GREEN);
            showLibrary();
            select(entry);
        });
    }

    /** A skin drawn for slim arms leaves the outer column of each arm empty. */
    static boolean looksSlim(BufferedImage skin) {
        if (skin.getHeight() < 64) {
            return false;
        }

        int unit = skin.getWidth() / 64;
        return (skin.getRGB(54 * unit, 20 * unit) >>> 24) == 0 && (skin.getRGB(55 * unit, 31 * unit) >>> 24) == 0;
    }

    private void addFromPlayer() {
        String name = JOptionPane.showInputDialog(app.frame(), "Whose skin? Their Minecraft name:", "Add a skin",
                JOptionPane.PLAIN_MESSAGE);

        if (name == null || name.isBlank()) {
            return;
        }

        app.run(() -> {
            MinecraftProfile.Skin skin = new MinecraftProfile().skinOf(name);

            if (skin == null) {
                throw new IOException("There is no Minecraft player called " + name.strip() + ".");
            }

            return library.add(name.strip(), png(texture(skin.url())), skin.slim());
        }, entry -> {
            app.toast("Added " + entry.name() + "'s skin.", Style.GREEN);
            showLibrary();
            select(entry);
        });
    }

    private void remove(SkinLibrary.Entry entry) {
        app.run(() -> {
            library.remove(entry);
            return entry;
        }, gone -> {
            if (selected != null && selected.id().equals(gone.id())) {
                selected = null;
                selectWorn();
            }

            showLibrary();
        });
    }

    private void wear(SkinLibrary.Entry entry) {
        busy = true;
        showPreview();
        app.run(() -> {
            Account account = Play.session(app.settings(), app.accounts());
            MinecraftProfile.State now = new MinecraftProfile().uploadSkin(account, library.read(entry), entry.slim());

            if (now.skin() != null) {
                texture(now.skin().url());
                app.accounts().put(new Account(account.id(), account.name(), now.skin().url(),
                        account.accessToken(), account.expiresAt(), account.refreshToken()));
                Heads.forget(account.id());
            }

            return now;
        }, now -> {
            busy = false;
            state = now;
            app.toast("You're wearing " + entry.name() + ". Other players see it the next time you join.",
                    Style.GREEN);
            app.changed();
        }, failure -> {
            busy = false;
            showPreview();
        });
    }

    // ------------------------------------------------------------- capes

    private void showCapes() {
        capes.removeAll();
        capeNote.removeAll();

        if (state == null && loadError != null && app.accounts().active() != null) {
            Widgets.Card card = new Widgets.Card(Style.PANEL, 14, true);
            card.setLayout(new BorderLayout(14, 0));
            card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
            JPanel words = Widgets.stack(4);
            words.add(Widgets.line("Your capes could not be read", Style.font(Font.BOLD, 14f), Style.TEXT));
            words.add(Widgets.paragraph(loadError, 12.5f, Style.MUTED, 3));
            card.add(words, BorderLayout.CENTER);
            Widgets.Button again = Widgets.button("Try again", Icons.Glyph.REFRESH, Widgets.Kind.SECONDARY);
            again.addActionListener(event -> {
                loadError = null;
                showCapes();
                refresh();
            });
            JPanel holder = Widgets.clear(new BorderLayout());
            holder.add(again, BorderLayout.NORTH);
            card.add(holder, BorderLayout.EAST);
            capeNote.add(card);
        } else if (state == null) {
            capes.add(ModsPage.empty("Capes", app.accounts().active() == null
                    ? "Sign in to see the capes your account has." : "Reading your capes..."));
        } else {
            capes.add(capeCard(null));

            for (MinecraftProfile.Cape cape : state.capes()) {
                capes.add(capeCard(cape));
            }

            if (state.capes().isEmpty()) {
                capes.add(ModsPage.empty("No capes yet", "Capes come from events and Minecraft purchases. "
                        + "Any you get show up here."));
            }
        }

        capeNote.revalidate();
        capes.revalidate();
        capes.repaint();
    }

    private JComponent capeCard(MinecraftProfile.Cape cape) {
        boolean worn = cape == null ? state.activeCape() == null : cape.active();
        Widgets.Card card = new Widgets.Card(worn ? Style.RAISED : Style.PANEL, 14, true)
                .hoverable(() -> wearCape(cape));
        card.setLayout(new BorderLayout(0, 8));
        card.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        JLabel picture = new JLabel();
        picture.setHorizontalAlignment(SwingConstants.CENTER);
        picture.setPreferredSize(new Dimension(10, 80));
        BufferedImage texture = cape == null ? null : TEXTURES.get(cape.url());

        if (texture != null) {
            picture.setIcon(new javax.swing.ImageIcon(PlayerArt.capeFront(texture, 80)));
        } else {
            picture.setIcon(Icons.of(cape == null ? Icons.Glyph.CLOSE : Icons.Glyph.SKIN, 28, Style.FAINT));
        }

        card.add(picture, BorderLayout.CENTER);
        JPanel foot = Widgets.stack(4);
        foot.add(Widgets.line(cape == null ? "No cape" : cape.alias() == null ? "Cape" : cape.alias(),
                Style.font(Font.BOLD, 13f), Style.TEXT));

        if (worn) {
            JPanel pill = Widgets.flow(0, 0);
            pill.add(Widgets.pill("Wearing", Style.GREEN));
            foot.add(pill);
        }

        card.add(foot, BorderLayout.SOUTH);
        return card;
    }

    private void wearCape(MinecraftProfile.Cape cape) {
        if (busy || state == null) {
            return;
        }

        busy = true;
        app.run(() -> {
            Account account = Play.session(app.settings(), app.accounts());
            MinecraftProfile profile = new MinecraftProfile();
            return cape == null ? profile.hideCape(account) : profile.showCape(account, cape.id());
        }, now -> {
            busy = false;
            state = now;
            capeTexture = activeCape();
            showCapes();
            showPreview();
            app.toast(cape == null ? "Your cape is off." : "You're wearing the " + (cape.alias() == null ? "cape"
                    : cape.alias() + " cape") + ".", Style.GREEN);
        }, failure -> busy = false);
    }

    // ------------------------------------------------------------ signed out

    private JComponent signInNotice() {
        Widgets.Card card = new Widgets.Card(Style.mix(Style.PANEL, Style.VIOLET, 0.08f), 14, false);
        card.setLayout(new BorderLayout(14, 0));
        card.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
        JPanel words = Widgets.stack(4);
        words.add(Widgets.line("Sign in to wear skins and capes", Style.font(Font.BOLD, 14f), Style.TEXT));
        words.add(Widgets.paragraph("Your saved skins stay here either way. Wearing one changes it on your "
                + "Minecraft account, everywhere you play.", 12.5f, Style.MUTED, 0));
        card.add(words, BorderLayout.CENTER);
        Widgets.Button signIn = Widgets.button("Sign in", Icons.Glyph.USER, Widgets.Kind.PRIMARY);
        signIn.addActionListener(event -> SignInSheet.show(app, account -> app.changed()));
        JPanel holder = Widgets.clear(new BorderLayout());
        holder.add(signIn, BorderLayout.NORTH);
        card.add(holder, BorderLayout.EAST);
        return card;
    }
}
