package studios.milkdromeda.octo.launcher.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import studios.milkdromeda.octo.launcher.PlainLoaders;
import studios.milkdromeda.octo.launcher.mods.ModLibrary;
import studios.milkdromeda.octo.launcher.mods.Modrinth;

/**
 * The mod manager: find a mod on Modrinth and install it, with what it needs,
 * in one click; turn installed mods on and off; and update them.
 */
final class ModsPage implements App.Page {
    /** Projects installed, by Modrinth id, so every Install button knows. */
    static final Set<String> INSTALLED = ConcurrentHashMap.newKeySet();
    private static final List<String> CATEGORIES = List.of("technology", "adventure", "magic", "storage",
            "utility", "decoration", "optimization", "worldgen", "food", "equipment", "mobs", "library");

    private final App app;
    private final JPanel view = Widgets.clear(new BorderLayout(0, 18));
    private final CardLayout tabs = new CardLayout();
    private final JPanel tabBody = Widgets.clear(tabs);
    private final Map<String, JLabel> tabLabels = new java.util.LinkedHashMap<>();

    // Browse
    private final Widgets.Field search = new Widgets.Field("Search mods on Modrinth", Icons.Glyph.SEARCH);
    private final JPanel results = Widgets.grid(360, 3, 14);
    private final Widgets.Button more = Widgets.button("Load more", null, Widgets.Kind.SECONDARY);
    private final Text resultCount = Widgets.line(" ", Style.font(Font.PLAIN, 12.5f), Style.FAINT);
    private final Widgets.Switch onlyThisVersion = new Widgets.Switch(false);
    private final Widgets.Button sortButton = Widgets.dropdown("Most relevant");
    private final Widgets.Button loaderButton = Widgets.dropdown("Every loader");
    /** Mods of one loader only, or of every one (null): Octo runs them all together. */
    private String loader;
    private final JPanel categoryRow = Widgets.flow(8, 8);
    private String sort = "relevance";
    private String category;
    private int offset;
    private int searchGeneration;
    private final Timer typing;

    // Installed
    private final JPanel installedList = Widgets.stack(8);
    private final Widgets.Field filter = new Widgets.Field("Filter installed mods", Icons.Glyph.SEARCH);
    private final JLabel installedCount = Widgets.text(" ", 12.5f, Font.PLAIN, Style.FAINT);
    private List<ModLibrary.InstalledMod> installed = List.of();
    private boolean installedLoaded;

    // Updates
    private final JPanel updateList = Widgets.stack(8);
    private final Text updateStatus = Widgets.line("Check Modrinth for newer versions of your mods.",
            Style.font(Font.PLAIN, 13f), Style.MUTED);
    private Map<Path, Modrinth.Version> updates = Map.of();

    ModsPage(App app) {
        this.app = app;
        view.setBorder(BorderFactory.createEmptyBorder(22, 24, 0, 24));

        JPanel head = Widgets.clear(new BorderLayout(20, 0));
        JPanel title = Widgets.stack(4);
        title.add(Widgets.heading("Mods"));
        title.add(Widgets.line("Fabric, Quilt, Forge and NeoForge, from any version: Octo makes them fit.",
                Style.font(Font.PLAIN, 13f), Style.MUTED));
        head.add(title, BorderLayout.CENTER);
        head.add(tabStrip(), BorderLayout.EAST);
        view.add(head, BorderLayout.NORTH);

        tabBody.add(browseTab(), "Browse");
        tabBody.add(installedTab(), "Installed");
        tabBody.add(updatesTab(), "Updates");
        view.add(tabBody, BorderLayout.CENTER);
        showTab(System.getProperty("octo.launcher.tab", "Browse"));

        typing = new Timer(350, event -> newSearch());
        typing.setRepeats(false);
        search.getDocument().addDocumentListener(onType(typing::restart));
        filter.getDocument().addDocumentListener(onType(this::showInstalled));

        for (int i = 0; i < 4; i++) {
            installedList.add(new Skeleton(64, true));
        }

        newSearch();
    }

    private static DocumentListener onType(Runnable action) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                action.run();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                action.run();
            }
        };
    }

    @Override
    public JComponent view() {
        return view;
    }

    /** The mods of the game Play starts: Octo's, or a plain loader's own, for that loader only. */
    static ModLibrary library(App app) {
        PlainLoaders.Loader chosen = app.settings().loader();
        return new ModLibrary(app.settings().playDir(), chosen.plain() ? chosen.key : null);
    }

    @Override
    public void refresh() {
        // A plain loader shows its own mods first; with Octo, every loader's.
        String plain = app.settings().loader().modrinth();

        if (plain != null && !plain.equals(loader)) {
            loader = plain;
            loaderButton.setText(app.settings().loader().label);
            newSearch();
        }

        ModLibrary library = library(app);
        app.run(library::list, list -> {
            installed = list;
            installedLoaded = true;
            INSTALLED.clear();
            list.stream().map(ModLibrary.InstalledMod::projectId).filter(java.util.Objects::nonNull)
                    .forEach(INSTALLED::add);
            tabLabels.get("Installed").setText("Installed  " + list.size());
            showInstalled();
            results.repaint();
        });
    }

    // ------------------------------------------------------------------ tabs

    private JComponent tabStrip() {
        JPanel strip = new JPanel(new GridLayout(1, 3, 4, 0)) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Style.smooth(graphics);
                g.setColor(Style.PANEL);
                g.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g.setColor(Style.LINE);
                g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g.dispose();
            }
        };
        strip.setOpaque(false);
        strip.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        for (String name : List.of("Browse", "Installed", "Updates")) {
            JLabel tab = new JLabel(name, JLabel.CENTER) {
                @Override
                protected void paintComponent(Graphics graphics) {
                    if (Boolean.TRUE.equals(getClientProperty("selected"))) {
                        Graphics2D g = Style.smooth(graphics);
                        g.setColor(Style.HOVER);
                        g.fillRoundRect(0, 0, getWidth(), getHeight(), 9, 9);
                        g.dispose();
                    }

                    super.paintComponent(graphics);
                }
            };
            tab.setFont(Style.font(Font.BOLD, 13f));
            tab.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
            tab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            tab.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    showTab(name);
                }
            });
            tabLabels.put(name, tab);
            strip.add(tab);
        }

        JPanel wrap = Widgets.clear(new BorderLayout());
        wrap.add(strip, BorderLayout.NORTH);
        return wrap;
    }

    private void showTab(String name) {
        tabs.show(tabBody, name);
        tabLabels.forEach((key, label) -> {
            label.putClientProperty("selected", key.equals(name));
            label.setForeground(key.equals(name) ? Style.TEXT : Style.MUTED);
            label.repaint();
        });
    }

    // ---------------------------------------------------------------- browse

    private JComponent browseTab() {
        JPanel tab = Widgets.clear(new BorderLayout(0, 14));
        JPanel tools = Widgets.stack(12);

        JPanel bar = Widgets.clear(new BorderLayout(14, 0));
        bar.add(search, BorderLayout.CENTER);
        JPanel right = Widgets.row(12);
        JPanel toggle = Widgets.row(8);
        JPanel switchHolder = Widgets.clear(new BorderLayout());
        switchHolder.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        switchHolder.add(onlyThisVersion, BorderLayout.NORTH);
        toggle.add(switchHolder);
        JLabel toggleLabel = Widgets.text("Only for my version", 13f, Font.PLAIN, Style.MUTED);
        toggleLabel.setBorder(BorderFactory.createEmptyBorder(9, 0, 0, 0));
        toggle.add(toggleLabel);
        onlyThisVersion.onChange(value -> newSearch());
        right.add(toggle);
        loaderButton.addActionListener(event -> chooseLoader());
        right.add(loaderButton);
        sortButton.addActionListener(event -> chooseSort());
        right.add(sortButton);
        bar.add(right, BorderLayout.EAST);
        tools.add(bar);
        buildCategories();
        tools.add(categoryRow);
        tab.add(tools, BorderLayout.NORTH);

        JPanel list = Widgets.stack(14);
        list.setBorder(BorderFactory.createEmptyBorder(0, 0, 22, 4));
        list.add(results);
        JPanel footer = Widgets.clear(new BorderLayout(12, 0));
        footer.add(resultCount, BorderLayout.CENTER);
        more.addActionListener(event -> loadPage());
        footer.add(more, BorderLayout.EAST);
        list.add(footer);
        tab.add(Widgets.page(list), BorderLayout.CENTER);
        return tab;
    }

    private void buildCategories() {
        categoryRow.removeAll();
        categoryRow.add(categoryChip("All", null));

        for (String name : CATEGORIES) {
            categoryRow.add(categoryChip(Character.toUpperCase(name.charAt(0)) + name.substring(1), name));
        }

        categoryRow.revalidate();
        categoryRow.repaint();
    }

    private JComponent categoryChip(String label, String value) {
        boolean selected = java.util.Objects.equals(category, value);
        JLabel chip = new JLabel(label) {
            private boolean hover;

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
                        category = value;
                        buildCategories();
                        newSearch();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Style.smooth(graphics);

                if (selected) {
                    g.setPaint(Style.accent(0, getWidth()));
                } else {
                    g.setColor(hover ? Style.HOVER : Style.RAISED);
                }

                g.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g.dispose();
                super.paintComponent(graphics);
            }
        };
        chip.setFont(Style.font(Font.BOLD, 12f));
        chip.setForeground(selected ? Color.WHITE : Style.MUTED);
        chip.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
        chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return chip;
    }

    private void chooseLoader() {
        JPopupMenu menu = new JPopupMenu();
        String[][] options = { { "", "Every loader" }, { "fabric", "Fabric" }, { "quilt", "Quilt" },
                { "forge", "Forge" }, { "neoforge", "NeoForge" } };

        for (String[] option : options) {
            JMenuItem item = new JMenuItem(option[1]);
            item.setFont(Style.font(Font.PLAIN, 13f));
            item.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 24));
            item.addActionListener(event -> {
                loader = option[0].isEmpty() ? null : option[0];
                loaderButton.setText(option[1]);
                newSearch();
            });
            menu.add(item);
        }

        menu.show(loaderButton, 0, loaderButton.getHeight() + 6);
    }

    private void chooseSort() {
        JPopupMenu menu = new JPopupMenu();
        String[][] options = { { "relevance", "Most relevant" }, { "downloads", "Most downloaded" },
                { "follows", "Most followed" }, { "updated", "Recently updated" }, { "newest", "Newest" } };

        for (String[] option : options) {
            JMenuItem item = new JMenuItem(option[1]);
            item.setFont(Style.font(Font.PLAIN, 13f));
            item.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 24));
            item.addActionListener(event -> {
                sort = option[0];
                sortButton.setText(option[1]);
                newSearch();
            });
            menu.add(item);
        }

        menu.show(sortButton, 0, sortButton.getHeight() + 6);
    }

    private void newSearch() {
        offset = 0;
        searchGeneration++;
        results.removeAll();

        for (int i = 0; i < 6; i++) {
            results.add(new Skeleton(132, true));
        }

        results.revalidate();
        results.repaint();
        loadPage();
    }

    private void loadPage() {
        int generation = searchGeneration;
        String query = search.getText().strip();
        String version = onlyThisVersion.isOn() ? app.selectedVersion() : null;
        more.setEnabled(false);
        resultCount.setText("Searching Modrinth...");
        String onlyLoader = loader;
        app.run(() -> Modrinth.search(query, version, category, onlyLoader, sort, offset, 24), page -> {
            if (generation != searchGeneration) {
                return;
            }

            if (offset == 0) {
                results.removeAll();
            }

            for (Modrinth.Project project : page.projects()) {
                results.add(resultCard(project));
            }

            if (page.projects().isEmpty() && offset == 0) {
                results.add(empty("Nothing found", "Try other words, another category, or turn off \"Only for my "
                        + "version\": Octo loads mods made for other versions too."));
            }

            offset += page.projects().size();
            resultCount.setText(String.format(Locale.ROOT, "Showing %d of %,d mods", offset, page.total()));
            more.setEnabled(offset < page.total());
            results.revalidate();
            results.repaint();
        }, failure -> {
            if (generation != searchGeneration) {
                return;
            }

            results.removeAll();
            results.add(empty("Modrinth could not be reached", "Check your connection and search again."));
            resultCount.setText(" ");
            more.setEnabled(true);
            results.revalidate();
            results.repaint();
        });
    }

    private JComponent resultCard(Modrinth.Project project) {
        Widgets.Card card = new Widgets.Card(Style.PANEL, 16, true).hoverable(() -> app.showMod(project));
        card.setLayout(new BorderLayout(14, 0));
        card.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        JLabel icon = new JLabel();
        icon.setVerticalAlignment(JLabel.TOP);
        Images.load(project.iconUrl(), project.title(), project.color() == 0 ? null : new Color(project.color()),
                64, 16, image -> icon.setIcon(new javax.swing.ImageIcon(image)));
        JPanel iconHolder = Widgets.clear(new BorderLayout());
        iconHolder.add(icon, BorderLayout.NORTH);
        card.add(iconHolder, BorderLayout.WEST);

        JPanel text = Widgets.stack(6);
        JPanel titleRow = Widgets.clear(new BorderLayout(10, 0));
        JPanel names = Widgets.stack(2);
        names.add(Widgets.line(project.title(), Style.font(Font.BOLD, 15f), Style.TEXT));
        names.add(Widgets.line("by " + project.author(), Style.font(Font.PLAIN, 12f), Style.FAINT));
        titleRow.add(names, BorderLayout.CENTER);
        JPanel action = Widgets.clear(new BorderLayout());
        action.add(installButton(app, project), BorderLayout.NORTH);
        titleRow.add(action, BorderLayout.EAST);
        text.add(titleRow);
        text.add(Widgets.paragraph(project.description(), 13f, Style.MUTED, 2));
        JPanel meta = Widgets.flow(6, 6);
        JLabel downloads = Widgets.text(Widgets.count(project.downloads()), 12f, Font.BOLD, Style.MUTED);
        downloads.setIcon(Icons.of(Icons.Glyph.DOWNLOAD, 14, Style.FAINT));
        downloads.setIconTextGap(4);
        downloads.setBorder(BorderFactory.createEmptyBorder(3, 0, 3, 6));
        meta.add(downloads);

        for (String loader : project.loaders()) {
            meta.add(Widgets.pill(loaderName(loader), loaderColour(loader)));
        }

        text.add(meta);
        card.add(text, BorderLayout.CENTER);
        return card;
    }

    static String loaderName(String loader) {
        return switch (loader) {
            case "neoforge" -> "NeoForge";
            case "fabric" -> "Fabric";
            case "forge" -> "Forge";
            case "quilt" -> "Quilt";
            default -> loader;
        };
    }

    static Color loaderColour(String loader) {
        return switch (loader.toLowerCase(Locale.ROOT)) {
            case "neoforge" -> new Color(0xFF, 0x96, 0x4F);
            case "fabric" -> new Color(0xD8, 0xC2, 0x8E);
            case "forge", "forge (legacy)" -> new Color(0x6E, 0x9B, 0xFF);
            case "quilt" -> new Color(0xC2, 0x8B, 0xFF);
            default -> Style.MUTED;
        };
    }

    /** One click: the mod and everything it requires, into the mods folder. */
    static Widgets.Button installButton(App app, Modrinth.Project project) {
        boolean already = INSTALLED.contains(project.id());
        Widgets.Button button = already
                ? new Widgets.Button("Installed", Icons.of(Icons.Glyph.CHECK, 15, Style.GREEN), Widgets.Kind.SECONDARY)
                : Widgets.button("Install", Icons.Glyph.DOWNLOAD, Widgets.Kind.PRIMARY);

        if (already) {
            button.setForeground(Style.GREEN);
        }

        button.setEnabled(!already);
        button.addActionListener(event -> {
            button.setEnabled(false);
            button.setText("Installing...");
            ModLibrary library = library(app);
            app.run(() -> library.install(project.id(), app.selectedVersion(), file -> { }), done -> {
                INSTALLED.add(project.id());
                button.setText("Installed");
                button.setIcon(Icons.of(Icons.Glyph.CHECK, 15, Style.GREEN));
                button.setForeground(Style.GREEN);
                int extra = done.added().size() - 1;
                app.toast("Installed " + project.title() + (extra > 0 ? " and " + extra + " mod"
                        + (extra == 1 ? "" : "s") + " it needs" : "") + ".", Style.GREEN);
                app.changed();
            }, failure -> {
                button.setEnabled(true);
                button.setText("Install");
            });
        });
        return button;
    }

    // ------------------------------------------------------------- installed

    private JComponent installedTab() {
        JPanel tab = Widgets.clear(new BorderLayout(0, 14));
        JPanel bar = Widgets.clear(new BorderLayout(14, 0));
        bar.add(filter, BorderLayout.CENTER);
        JPanel right = Widgets.row(12);
        installedCount.setBorder(BorderFactory.createEmptyBorder(9, 0, 0, 0));
        right.add(installedCount);
        Widgets.Button folder = Widgets.button("Open folder", Icons.Glyph.FOLDER, Widgets.Kind.SECONDARY);
        folder.addActionListener(event -> open(library(app).folder()));
        right.add(folder);
        bar.add(right, BorderLayout.EAST);
        tab.add(bar, BorderLayout.NORTH);
        installedList.setBorder(BorderFactory.createEmptyBorder(0, 0, 22, 4));
        tab.add(Widgets.page(installedList), BorderLayout.CENTER);
        return tab;
    }

    private void showInstalled() {
        if (!installedLoaded) {
            return;
        }

        installedList.removeAll();
        String query = filter.getText().strip().toLowerCase(Locale.ROOT);
        List<ModLibrary.InstalledMod> shown = new ArrayList<>();

        for (ModLibrary.InstalledMod mod : installed) {
            if (query.isEmpty() || mod.name().toLowerCase(Locale.ROOT).contains(query)
                    || mod.fileName().toLowerCase(Locale.ROOT).contains(query)) {
                shown.add(mod);
            }
        }

        long on = installed.stream().filter(ModLibrary.InstalledMod::enabled).count();
        installedCount.setText(on + " on  ·  " + (installed.size() - on) + " off");

        if (installed.isEmpty()) {
            installedList.add(empty("No mods yet", "Find one in Browse and press Install: it goes into your mods "
                    + "folder, with everything it needs."));
        }

        for (ModLibrary.InstalledMod mod : shown) {
            installedList.add(installedRow(mod));
        }

        installedList.revalidate();
        installedList.repaint();
    }

    private JComponent installedRow(ModLibrary.InstalledMod mod) {
        Widgets.Card row = new Widgets.Card(Style.PANEL, 14, true);
        row.setLayout(new BorderLayout(14, 0));
        row.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        JPanel iconHolder = Widgets.clear(new BorderLayout());
        iconHolder.add(Widgets.picture(Images.fromBytes(mod.icon(), mod.name(), 44, 11)), BorderLayout.NORTH);
        row.add(iconHolder, BorderLayout.WEST);

        JPanel text = Widgets.stack(4);
        JPanel line = Widgets.flow(8, 4);
        JLabel name = Widgets.text(mod.name(), 14.5f, Font.BOLD, mod.enabled() ? Style.TEXT : Style.FAINT);
        line.add(name);

        if (mod.loader() != null && !mod.loader().isBlank()) {
            line.add(Widgets.pill(mod.loader(), loaderColour(mod.loader())));
        }

        if (!mod.enabled()) {
            line.add(Widgets.pill("Off", Style.FAINT));
        }

        text.add(line);
        text.add(Widgets.line((mod.version() == null ? "" : mod.version() + "   ·   ") + mod.fileName(),
                Style.font(Font.PLAIN, 12f), Style.FAINT));
        row.add(text, BorderLayout.CENTER);

        JPanel actions = Widgets.row(10);
        Widgets.Switch toggle = new Widgets.Switch(mod.enabled());
        toggle.setToolTipText(mod.enabled() ? "Turn off" : "Turn on");
        toggle.onChange(value -> app.run(() -> library(app).setEnabled(mod, value),
                changed -> {
                    app.toast((value ? "Turned on " : "Turned off ") + mod.name() + ".", Style.MUTED);
                    refresh();
                }));
        JPanel toggleHolder = Widgets.clear(new BorderLayout());
        toggleHolder.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        toggleHolder.add(toggle, BorderLayout.NORTH);
        actions.add(toggleHolder);
        Widgets.Button delete = Widgets.iconButton(Icons.Glyph.TRASH, "Remove", Widgets.Kind.DANGER);
        delete.addActionListener(event -> {
            if (JOptionPane.showConfirmDialog(app.frame(), "Remove " + mod.name() + "?\n" + mod.fileName()
                    + " is deleted from the mods folder.", "Remove mod", JOptionPane.OK_CANCEL_OPTION)
                    == JOptionPane.OK_OPTION) {
                app.run(() -> {
                    library(app).delete(mod);
                    return mod;
                }, removed -> {
                    app.toast("Removed " + mod.name() + ".", Style.MUTED);
                    app.changed();
                });
            }
        });
        actions.add(delete);
        JPanel actionHolder = Widgets.clear(new BorderLayout());
        actionHolder.add(actions, BorderLayout.NORTH);
        row.add(actionHolder, BorderLayout.EAST);
        return row;
    }

    // --------------------------------------------------------------- updates

    private JComponent updatesTab() {
        JPanel tab = Widgets.clear(new BorderLayout(0, 14));
        JPanel bar = Widgets.clear(new BorderLayout(14, 0));
        JPanel status = Widgets.clear(new BorderLayout());
        status.setBorder(BorderFactory.createEmptyBorder(9, 0, 0, 0));
        status.add(updateStatus, BorderLayout.NORTH);
        bar.add(status, BorderLayout.CENTER);
        JPanel right = Widgets.row(10);
        Widgets.Button check = Widgets.button("Check for updates", Icons.Glyph.REFRESH, Widgets.Kind.SECONDARY);
        check.addActionListener(event -> checkUpdates());
        Widgets.Button all = Widgets.button("Update all", Icons.Glyph.UPDATE, Widgets.Kind.PRIMARY);
        all.addActionListener(event -> updateAll());
        right.add(check);
        right.add(all);
        bar.add(right, BorderLayout.EAST);
        tab.add(bar, BorderLayout.NORTH);
        updateList.setBorder(BorderFactory.createEmptyBorder(0, 0, 22, 4));
        updateList.add(empty("Keep your mods current", "Octo looks each mod up on Modrinth by its file, so mods "
                + "you installed by hand are found too. Press Check for updates."));
        tab.add(Widgets.page(updateList), BorderLayout.CENTER);
        return tab;
    }

    private void checkUpdates() {
        updateStatus.setText("Checking " + installed.size() + " mods on Modrinth...");
        updateList.removeAll();

        for (int i = 0; i < 3; i++) {
            updateList.add(new Skeleton(64, true));
        }

        updateList.revalidate();
        ModLibrary library = library(app);
        app.run(() -> library.updates(app.selectedVersion()), found -> {
            updates = found;
            tabLabels.get("Updates").setText(found.isEmpty() ? "Updates" : "Updates  " + found.size());
            updateStatus.setText(found.isEmpty() ? "Everything is up to date." : found.size() + " update"
                    + (found.size() == 1 ? "" : "s") + " available.");
            showUpdates();
        }, failure -> {
            updateStatus.setText("Modrinth could not be reached.");
            updateList.removeAll();
            updateList.revalidate();
        });
    }

    private void showUpdates() {
        updateList.removeAll();

        if (updates.isEmpty()) {
            updateList.add(empty("Everything is up to date", "Every mod Modrinth knows is at its newest version "
                    + "for your Minecraft."));
        }

        for (Map.Entry<Path, Modrinth.Version> entry : updates.entrySet()) {
            ModLibrary.InstalledMod mod = installed.stream().filter(candidate -> candidate.file().equals(entry.getKey()))
                    .findFirst().orElse(null);

            if (mod == null) {
                continue;
            }

            Widgets.Card row = new Widgets.Card(Style.PANEL, 14, true);
            row.setLayout(new BorderLayout(14, 0));
            row.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
            row.add(Widgets.picture(Images.fromBytes(mod.icon(), mod.name(), 40, 10)), BorderLayout.WEST);
            JPanel text = Widgets.stack(3);
            text.add(Widgets.line(mod.name(), Style.font(Font.BOLD, 14.5f), Style.TEXT));
            text.add(Widgets.line((mod.version() == null ? mod.fileName() : mod.version()) + "   →   "
                    + entry.getValue().number(), Style.font(Font.PLAIN, 12.5f), Style.MUTED));
            row.add(text, BorderLayout.CENTER);
            Widgets.Button update = Widgets.button("Update", Icons.Glyph.DOWNLOAD, Widgets.Kind.PRIMARY);
            update.addActionListener(event -> {
                update.setEnabled(false);
                update.setText("Updating...");
                app.run(() -> library(app).update(mod, entry.getValue()), done -> {
                    app.toast("Updated " + mod.name() + " to " + entry.getValue().number() + ".", Style.GREEN);
                    update.setText("Updated");
                    app.changed();
                });
            });
            JPanel action = Widgets.clear(new BorderLayout());
            action.add(update, BorderLayout.NORTH);
            row.add(action, BorderLayout.EAST);
            updateList.add(row);
        }

        updateList.revalidate();
        updateList.repaint();
    }

    private void updateAll() {
        if (updates.isEmpty()) {
            checkUpdates();
            return;
        }

        Map<Path, Modrinth.Version> todo = Map.copyOf(updates);
        app.run(() -> {
            ModLibrary library = library(app);
            int count = 0;

            for (ModLibrary.InstalledMod mod : library.list()) {
                Modrinth.Version version = todo.get(mod.file());

                if (version != null) {
                    library.update(mod, version);
                    count++;
                }
            }

            return count;
        }, count -> {
            app.toast("Updated " + count + " mod" + (count == 1 ? "" : "s") + ".", Style.GREEN);
            updates = Map.of();
            showUpdates();
            app.changed();
        });
    }

    // ---------------------------------------------------------------- common

    /** A card saying there is nothing here, and what to do about it. */
    static JComponent empty(String title, String body) {
        Widgets.Card card = Widgets.card();
        JPanel text = Widgets.stack(6);
        text.add(Widgets.line(title, Style.font(Font.BOLD, 15f), Style.TEXT));
        text.add(Widgets.paragraph(body, 13f, Style.MUTED, 0));
        card.add(text, BorderLayout.CENTER);
        return card;
    }

    static void open(Path folder) {
        try {
            java.nio.file.Files.createDirectories(folder);
            Desktop.getDesktop().open(folder.toFile());
        } catch (Exception e) {
            // No file manager: nothing to do.
        }
    }
}
