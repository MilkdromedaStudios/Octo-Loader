package studios.milkdromeda.octo.launcher.ui;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
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
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

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
    private final JPanel results = Widgets.clear(new GridLayout(0, 2, 14, 14));
    private final Widgets.Button more = Widgets.button("Load more", null, Widgets.Kind.SECONDARY);
    private final JLabel resultCount = Widgets.text(" ", 12.5f, Font.PLAIN, Style.FAINT);
    private final Widgets.Switch onlyThisVersion = new Widgets.Switch(false);
    private final Widgets.Button sortButton = Widgets.dropdown("Most relevant");
    private final JPanel categoryRow = Widgets.row(8);
    private String sort = "relevance";
    private String category;
    private int offset;
    private int searchGeneration;
    private final Timer typing;

    // Installed
    private final JPanel installedList = Widgets.column();
    private final Widgets.Field filter = new Widgets.Field("Filter installed mods", Icons.Glyph.SEARCH);
    private final JLabel installedCount = Widgets.text(" ", 12.5f, Font.PLAIN, Style.FAINT);
    private List<ModLibrary.InstalledMod> installed = List.of();

    // Updates
    private final JPanel updateList = Widgets.column();
    private final JLabel updateStatus = Widgets.text("Check Modrinth for newer versions of your mods.", 13f,
            Font.PLAIN, Style.MUTED);
    private Map<Path, Modrinth.Version> updates = Map.of();

    ModsPage(App app) {
        this.app = app;
        view.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));

        JPanel head = Widgets.clear(new BorderLayout());
        JPanel title = Widgets.column();
        title.add(Widgets.heading("Mods"));
        title.add(Box.createVerticalStrut(4));
        title.add(Widgets.text("Fabric, Quilt, Forge and NeoForge, from any version: Octo makes them fit.", 13f,
                Font.PLAIN, Style.MUTED));
        head.add(title, BorderLayout.WEST);
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

    @Override
    public void refresh() {
        ModLibrary library = new ModLibrary(app.settings().gameDir());
        app.run(library::list, list -> {
            installed = list;
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
            tab.setBorder(BorderFactory.createEmptyBorder(7, 18, 7, 18));
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
        JPanel tools = Widgets.column();

        JPanel bar = Widgets.clear(new BorderLayout(12, 0));
        bar.add(search, BorderLayout.CENTER);
        JPanel right = Widgets.row(12);
        JPanel toggle = Widgets.row(8);
        toggle.add(onlyThisVersion);
        toggle.add(Widgets.text("Only for my version", 13f, Font.PLAIN, Style.MUTED));
        onlyThisVersion.onChange(value -> newSearch());
        right.add(toggle);
        sortButton.addActionListener(event -> chooseSort());
        right.add(sortButton);
        bar.add(right, BorderLayout.EAST);
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        tools.add(bar);
        tools.add(Box.createVerticalStrut(12));

        categoryRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        buildCategories();
        tools.add(categoryRow);
        tab.add(tools, BorderLayout.NORTH);

        JPanel list = Widgets.column();
        results.setAlignmentX(Component.LEFT_ALIGNMENT);
        list.add(results);
        list.add(Box.createVerticalStrut(14));
        JPanel footer = Widgets.clear(new BorderLayout());
        footer.add(resultCount, BorderLayout.WEST);
        more.addActionListener(event -> loadPage());
        footer.add(more, BorderLayout.EAST);
        footer.setAlignmentX(Component.LEFT_ALIGNMENT);
        footer.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        list.add(footer);
        JPanel holder = new Widgets.Tracking();
        holder.add(list, BorderLayout.NORTH);
        tab.add(Widgets.scroll(holder), BorderLayout.CENTER);
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
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g = Style.smooth(graphics);

                if (selected) {
                    g.setPaint(Style.accent(0, getWidth()));
                } else {
                    g.setColor(Style.RAISED);
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
        chip.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                category = value;
                buildCategories();
                newSearch();
            }
        });
        return chip;
    }

    private void chooseSort() {
        JPopupMenu menu = new JPopupMenu();
        String[][] options = { { "relevance", "Most relevant" }, { "downloads", "Most downloaded" },
                { "follows", "Most followed" }, { "updated", "Recently updated" }, { "newest", "Newest" } };

        for (String[] option : options) {
            JMenuItem item = new JMenuItem(option[1]);
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
        results.revalidate();
        results.repaint();
        loadPage();
    }

    private void loadPage() {
        int generation = searchGeneration;
        String query = search.getText().strip();
        String version = onlyThisVersion.isOn() ? app.selectedVersion() : null;
        more.setEnabled(false);
        resultCount.setText("Searching...");
        app.run(() -> Modrinth.search(query, version, category, sort, offset, 20), page -> {
            if (generation != searchGeneration) {
                return;
            }

            for (Modrinth.Project project : page.projects()) {
                results.add(resultCard(project));
            }

            offset += page.projects().size();
            resultCount.setText(String.format(Locale.ROOT, "Showing %d of %,d mods", offset, page.total()));
            more.setEnabled(offset < page.total());
            results.revalidate();
            results.repaint();
        }, failure -> {
            resultCount.setText("Modrinth could not be reached. Check your connection and search again.");
            more.setEnabled(true);
        });
    }

    private JComponent resultCard(Modrinth.Project project) {
        Widgets.Card card = new Widgets.Card(Style.PANEL, 16, true).hoverable(null);
        card.setLayout(new BorderLayout(14, 0));
        card.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        JLabel icon = new JLabel();
        icon.setVerticalAlignment(JLabel.TOP);
        Images.load(project.iconUrl(), project.title(), project.color() == 0 ? null : new Color(project.color()),
                64, 14, image -> icon.setIcon(new javax.swing.ImageIcon(image)));
        card.add(icon, BorderLayout.WEST);

        JPanel text = Widgets.column();
        JLabel title = Widgets.text("<html><b>" + HomePage.escape(HomePage.shorten(project.title(), 34))
                + "</b> <span style='color:" + Style.hex(Style.FAINT) + "'>by "
                + HomePage.escape(project.author()) + "</span></html>", 14f, Font.PLAIN, Style.TEXT);
        title.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        title.setToolTipText("Open on Modrinth");
        title.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                HomePage.browse(project.pageUrl());
            }
        });
        text.add(title);
        text.add(Box.createVerticalStrut(5));
        text.add(Widgets.wrapped(HomePage.escape(HomePage.shorten(project.description(), 120)), 12.5f, Style.MUTED,
                260));
        text.add(Box.createVerticalStrut(8));
        JPanel meta = Widgets.row(6);
        meta.add(new JLabel(Icons.of(Icons.Glyph.DOWNLOAD, 14, Style.FAINT)));
        meta.add(Widgets.text(Widgets.count(project.downloads()), 12f, Font.PLAIN, Style.FAINT));
        meta.add(Box.createHorizontalStrut(6));

        for (String loader : project.loaders()) {
            meta.add(Widgets.pill(loaderName(loader), loaderColour(loader)));
        }

        meta.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(meta);
        card.add(text, BorderLayout.CENTER);

        JPanel action = Widgets.clear(new BorderLayout());
        action.add(installButton(app, project, false), BorderLayout.NORTH);
        card.add(action, BorderLayout.EAST);
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
    static JComponent installButton(App app, Modrinth.Project project, boolean wide) {
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
            ModLibrary library = new ModLibrary(app.settings().gameDir());
            app.run(() -> library.install(project.id(), app.selectedVersion(), file -> { }), done -> {
                INSTALLED.add(project.id());
                button.setText("Installed");
                button.setIcon(Icons.of(Icons.Glyph.CHECK, 15, Style.GREEN));
                button.setForeground(Style.GREEN);
                int extra = done.added().size() - 1;
                app.toast("Installed " + project.title() + (extra > 0 ? " and " + extra + " mod"
                        + (extra == 1 ? "" : "s") + " it needs" : "") + ".", Style.GREEN);
                app.changed();
            });
        });
        return button;
    }

    // ------------------------------------------------------------- installed

    private JComponent installedTab() {
        JPanel tab = Widgets.clear(new BorderLayout(0, 14));
        JPanel bar = Widgets.clear(new BorderLayout(12, 0));
        bar.add(filter, BorderLayout.CENTER);
        JPanel right = Widgets.row(10);
        right.add(installedCount);
        Widgets.Button folder = Widgets.button("Open folder", Icons.Glyph.FOLDER, Widgets.Kind.SECONDARY);
        folder.addActionListener(event -> open(new ModLibrary(app.settings().gameDir()).folder()));
        right.add(folder);
        bar.add(right, BorderLayout.EAST);
        tab.add(bar, BorderLayout.NORTH);
        JPanel holder = new Widgets.Tracking();
        holder.add(installedList, BorderLayout.NORTH);
        tab.add(Widgets.scroll(holder), BorderLayout.CENTER);
        return tab;
    }

    private void showInstalled() {
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
        installedCount.setText(on + " on, " + (installed.size() - on) + " off");

        if (installed.isEmpty()) {
            installedList.add(empty("No mods yet", "Find one in Browse and press Install: it goes into "
                    + new ModLibrary(app.settings().gameDir()).folder() + ", with everything it needs."));
        }

        for (ModLibrary.InstalledMod mod : shown) {
            installedList.add(installedRow(mod));
            installedList.add(Box.createVerticalStrut(8));
        }

        installedList.revalidate();
        installedList.repaint();
    }

    private JComponent installedRow(ModLibrary.InstalledMod mod) {
        Widgets.Card row = new Widgets.Card(Style.PANEL, 14, true);
        row.setLayout(new BorderLayout(14, 0));
        row.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        row.add(Widgets.picture(Images.fromBytes(mod.icon(), mod.name(), 44, 11)), BorderLayout.WEST);

        JPanel text = Widgets.column();
        JPanel line = Widgets.row(8);
        line.add(Widgets.text(mod.name(), 14f, Font.BOLD, mod.enabled() ? Style.TEXT : Style.FAINT));

        if (mod.loader() != null && !mod.loader().isBlank()) {
            line.add(Widgets.pill(mod.loader(), loaderColour(mod.loader())));
        }

        if (!mod.enabled()) {
            line.add(Widgets.pill("Off", Style.FAINT));
        }

        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(line);
        text.add(Box.createVerticalStrut(4));
        JLabel detail = Widgets.text((mod.version() == null ? "" : mod.version() + "  ·  ") + mod.fileName(), 12f,
                Font.PLAIN, Style.FAINT);
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        text.add(detail);
        row.add(text, BorderLayout.CENTER);

        JPanel actions = Widgets.row(10);
        Widgets.Switch toggle = new Widgets.Switch(mod.enabled());
        toggle.setToolTipText(mod.enabled() ? "Turn off" : "Turn on");
        toggle.onChange(value -> app.run(() -> new ModLibrary(app.settings().gameDir()).setEnabled(mod, value),
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
                    new ModLibrary(app.settings().gameDir()).delete(mod);
                    return mod;
                }, removed -> {
                    app.toast("Removed " + mod.name() + ".", Style.MUTED);
                    app.changed();
                });
            }
        });
        actions.add(delete);
        row.add(actions, BorderLayout.EAST);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));
        return row;
    }

    // --------------------------------------------------------------- updates

    private JComponent updatesTab() {
        JPanel tab = Widgets.clear(new BorderLayout(0, 14));
        JPanel bar = Widgets.clear(new BorderLayout(12, 0));
        bar.add(updateStatus, BorderLayout.WEST);
        JPanel right = Widgets.row(10);
        Widgets.Button check = Widgets.button("Check for updates", Icons.Glyph.REFRESH, Widgets.Kind.SECONDARY);
        check.addActionListener(event -> checkUpdates());
        Widgets.Button all = Widgets.button("Update all", Icons.Glyph.UPDATE, Widgets.Kind.PRIMARY);
        all.addActionListener(event -> updateAll());
        right.add(check);
        right.add(all);
        bar.add(right, BorderLayout.EAST);
        tab.add(bar, BorderLayout.NORTH);
        JPanel holder = new Widgets.Tracking();
        holder.add(updateList, BorderLayout.NORTH);
        tab.add(Widgets.scroll(holder), BorderLayout.CENTER);
        return tab;
    }

    private void checkUpdates() {
        updateStatus.setText("Checking " + installed.size() + " mods on Modrinth...");
        ModLibrary library = new ModLibrary(app.settings().gameDir());
        app.run(() -> library.updates(app.selectedVersion()), found -> {
            updates = found;
            tabLabels.get("Updates").setText(found.isEmpty() ? "Updates" : "Updates  " + found.size());
            updateStatus.setText(found.isEmpty() ? "Everything is up to date." : found.size() + " update"
                    + (found.size() == 1 ? "" : "s") + " available.");
            showUpdates();
        });
    }

    private void showUpdates() {
        updateList.removeAll();

        for (Map.Entry<Path, Modrinth.Version> entry : updates.entrySet()) {
            ModLibrary.InstalledMod mod = installed.stream().filter(candidate -> candidate.file().equals(entry.getKey()))
                    .findFirst().orElse(null);

            if (mod == null) {
                continue;
            }

            Widgets.Card row = new Widgets.Card(Style.PANEL, 14, true);
            row.setLayout(new BorderLayout(14, 0));
            row.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            row.add(Widgets.picture(Images.fromBytes(mod.icon(), mod.name(), 40, 10)), BorderLayout.WEST);
            JPanel text = Widgets.column();
            text.add(Widgets.text(mod.name(), 14f, Font.BOLD, Style.TEXT));
            text.add(Box.createVerticalStrut(3));
            text.add(Widgets.text((mod.version() == null ? mod.fileName() : mod.version()) + "  →  "
                    + entry.getValue().number(), 12.5f, Font.PLAIN, Style.MUTED));
            row.add(text, BorderLayout.CENTER);
            Widgets.Button update = Widgets.button("Update", Icons.Glyph.DOWNLOAD, Widgets.Kind.PRIMARY);
            update.addActionListener(event -> {
                update.setEnabled(false);
                update.setText("Updating...");
                app.run(() -> new ModLibrary(app.settings().gameDir()).update(mod, entry.getValue()), done -> {
                    app.toast("Updated " + mod.name() + " to " + entry.getValue().number() + ".", Style.GREEN);
                    update.setText("Updated");
                    app.changed();
                });
            });
            JPanel action = Widgets.clear(new BorderLayout());
            action.add(update, BorderLayout.NORTH);
            row.add(action, BorderLayout.EAST);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 66));
            updateList.add(row);
            updateList.add(Box.createVerticalStrut(8));
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
            ModLibrary library = new ModLibrary(app.settings().gameDir());
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

    static JComponent empty(String title, String body) {
        Widgets.Card card = Widgets.card();
        JPanel text = Widgets.column();
        text.add(Widgets.text(title, 15f, Font.BOLD, Style.TEXT));
        text.add(Box.createVerticalStrut(6));
        text.add(Widgets.wrapped(HomePage.escape(body), 13f, Style.MUTED, 560));
        card.add(text, BorderLayout.CENTER);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
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
