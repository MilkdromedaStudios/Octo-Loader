package studios.milkdromeda.octo.launcher;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;

import studios.milkdromeda.octo.common.WorldBackups;
import studios.milkdromeda.octo.common.WorldContent;
import studios.milkdromeda.octo.installer.ClientInstaller;
import studios.milkdromeda.octo.installer.FlatButton;
import studios.milkdromeda.octo.installer.FlatControls;
import studios.milkdromeda.octo.installer.Installer;
import studios.milkdromeda.octo.installer.Mark;
import studios.milkdromeda.octo.installer.Theme;

/**
 * The Octo Launcher window: a sidebar of four pages — Play, Versions, Worlds,
 * Settings — and a banner along the top when there is something newer.
 */
final class LauncherUi {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault());

    private final JFrame frame = new JFrame("Octo Launcher");
    private final LauncherSettings settings = LauncherSettings.load();
    private final CardLayout pages = new CardLayout();
    private final JPanel pageHolder = new JPanel(pages);
    private final Map<String, JLabel> nav = new LinkedHashMap<>();
    private final JLabel status = new JLabel(" ");
    private final JPanel banner = new JPanel(new BorderLayout(12, 0));
    private final JLabel bannerText = new JLabel();
    private final FlatButton bannerAction = new FlatButton("Update", FlatButton.Kind.PRIMARY);

    // Play
    private final JComboBox<String> playVersion = FlatControls.comboBox();
    private final JLabel playWorld = FlatControls.caption(" ");
    // Versions
    private final DefaultListModel<Versions.Version> versionModel = new DefaultListModel<>();
    private final JList<Versions.Version> versionList = new JList<>(versionModel);
    // Worlds
    private final DefaultListModel<Worlds.World> worldModel = new DefaultListModel<>();
    private final JList<Worlds.World> worldList = new JList<>(worldModel);
    private final DefaultListModel<WorldBackups.Backup> backupModel = new DefaultListModel<>();
    private final JList<WorldBackups.Backup> backupList = new JList<>(backupModel);
    private final FlatButton acceptRemoval = new FlatButton("Accept removal", FlatButton.Kind.DANGER);
    private final JLabel worldDetail = FlatControls.caption(" ");

    private Updates.Release pending;

    static void show() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            Theme.applyToDialogs();
        } catch (Exception e) {
            // The window paints itself.
        }

        SwingUtilities.invokeLater(() -> new LauncherUi().open());
    }

    private void open() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BACKGROUND);
        root.add(sidebar(), BorderLayout.WEST);

        JPanel main = new JPanel(new BorderLayout(0, 14));
        main.setOpaque(false);
        main.setBorder(BorderFactory.createEmptyBorder(22, 26, 16, 26));
        main.add(banner(), BorderLayout.NORTH);

        pageHolder.setOpaque(false);
        pageHolder.add(playPage(), "Play");
        pageHolder.add(versionsPage(), "Versions");
        pageHolder.add(worldsPage(), "Worlds");
        pageHolder.add(settingsPage(), "Settings");
        main.add(pageHolder, BorderLayout.CENTER);

        status.setFont(Theme.ui(Font.PLAIN, 12f));
        status.setForeground(Theme.TEXT_MUTED);
        main.add(status, BorderLayout.SOUTH);
        root.add(main, BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.setIconImages(Mark.icons());
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(940, 620));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        go(System.getProperty("octo.launcher.page", "Play"));
        refreshVersions();
        refreshWorlds();

        if (settings.checkForUpdates) {
            checkForUpdates(false);
        }
    }

    // ------------------------------------------------------------- chrome

    private JComponent sidebar() {
        JPanel side = FlatControls.column();
        side.setOpaque(true);
        side.setBackground(Theme.SURFACE);
        side.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER),
                BorderFactory.createEmptyBorder(22, 18, 18, 18)));
        side.setPreferredSize(new Dimension(210, 10));

        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        brand.setOpaque(false);
        brand.add(new Mark(36));
        JLabel name = new JLabel("<html><b>Octo</b><br><span style='color:#98A0B0'>Launcher</span></html>");
        name.setFont(Theme.ui(Font.PLAIN, 15f));
        name.setForeground(Theme.TEXT);
        brand.add(name);
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 50));
        side.add(brand);
        side.add(Box.createVerticalStrut(26));

        for (String page : List.of("Play", "Versions", "Worlds", "Settings")) {
            JLabel item = new JLabel(page);
            item.setFont(Theme.ui(Font.BOLD, 14f));
            item.setForeground(Theme.TEXT_MUTED);
            item.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
            item.setOpaque(false);
            item.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            item.setAlignmentX(Component.LEFT_ALIGNMENT);
            item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
            item.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    go(page);
                }
            });
            nav.put(page, item);
            side.add(item);
            side.add(Box.createVerticalStrut(4));
        }

        side.add(Box.createVerticalGlue());
        JLabel version = new JLabel("<html>" + Installer.VERSION + "<br>" + Launcher.build() + "</html>");
        version.setFont(Theme.ui(Font.PLAIN, 11f));
        version.setForeground(Theme.TEXT_FAINT);
        version.setAlignmentX(Component.LEFT_ALIGNMENT);
        side.add(version);
        return side;
    }

    private void go(String page) {
        pages.show(pageHolder, page);
        nav.forEach((name, item) -> {
            boolean selected = name.equals(page);
            item.setOpaque(selected);
            item.setBackground(Theme.SURFACE_RAISED);
            item.setForeground(selected ? Theme.TEXT : Theme.TEXT_MUTED);
            item.repaint();
        });
    }

    private JComponent banner() {
        banner.setBackground(FlatControls.mix(Theme.ACCENT, Theme.BACKGROUND, 0.82f));
        banner.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 10));
        bannerText.setForeground(Theme.TEXT);
        bannerText.setFont(Theme.ui(Font.PLAIN, 13f));
        banner.add(bannerText, BorderLayout.CENTER);
        banner.add(bannerAction, BorderLayout.EAST);
        bannerAction.addActionListener(event -> applyUpdate());
        banner.setVisible(false);
        return banner;
    }

    private JPanel page(String title, String caption) {
        JPanel page = new JPanel(new BorderLayout(0, 16));
        page.setOpaque(false);
        JPanel head = FlatControls.column();
        JLabel heading = FlatControls.title(title);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(heading);
        head.add(Box.createVerticalStrut(4));
        JLabel sub = FlatControls.caption("<html><div style='width:520px'>" + caption + "</div></html>");
        sub.setAlignmentX(Component.LEFT_ALIGNMENT);
        head.add(sub);
        page.add(head, BorderLayout.NORTH);
        return page;
    }

    private static JComponent scroll(JComponent view) {
        javax.swing.JScrollPane pane = FlatControls.scroller(view);
        pane.setHorizontalScrollBarPolicy(javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return pane;
    }

    private static JPanel padded(JPanel card) {
        card.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));

        if (card.getLayout() instanceof BorderLayout layout) {
            layout.setVgap(12);
        }

        return card;
    }

    // --------------------------------------------------------------- Play

    private JComponent playPage() {
        JPanel page = page("Play", "Octo installs itself for the version, backs up your last world, and opens the "
                + "Minecraft Launcher on it.");
        JPanel card = padded(FlatControls.card());
        JPanel body = FlatControls.column();

        JLabel label = FlatControls.fieldLabel("Minecraft version");
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(label);
        body.add(Box.createVerticalStrut(6));
        playVersion.setAlignmentX(Component.LEFT_ALIGNMENT);
        playVersion.setMaximumSize(new Dimension(360, 40));
        playVersion.addActionListener(event -> {
            Object selected = playVersion.getSelectedItem();

            if (selected != null && !selected.toString().equals(settings.selectedVersion)) {
                settings.selectedVersion = selected.toString();
                saveSettings();
            }
        });
        body.add(playVersion);
        body.add(Box.createVerticalStrut(14));
        playWorld.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(playWorld);
        body.add(Box.createVerticalStrut(20));

        FlatButton play = new FlatButton("Play", FlatButton.Kind.PRIMARY);
        play.setFont(Theme.ui(Font.BOLD, 16f));
        play.setAlignmentX(Component.LEFT_ALIGNMENT);
        play.addActionListener(event -> background("Getting ready...", () -> Play.prepareAndOpen(settings,
                (String) playVersion.getSelectedItem(), this::log), message -> {
                    say(message, Theme.SUCCESS);
                    refreshWorlds();
                    refreshVersions();
                }));
        body.add(play);
        body.add(Box.createVerticalStrut(18));

        JLabel note = FlatControls.caption("<html>Signing in, downloading Minecraft and starting it are done by the "
                + "official Minecraft Launcher, with your Microsoft account. Octo never sees your sign-in.</html>");
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(note);
        card.add(body, BorderLayout.NORTH);
        page.add(card, BorderLayout.CENTER);
        return page;
    }

    // ----------------------------------------------------------- Versions

    private JComponent versionsPage() {
        JPanel page = page("Versions", "Every Minecraft version Mojang lists. Install Octo for one and it appears "
                + "in the Minecraft Launcher; it downloads the game itself the first time you play.");
        JPanel card = padded(FlatControls.card());
        FlatControls.styleList(versionList);
        versionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        versionList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                    boolean focus) {
                Versions.Version version = (Versions.Version) value;
                String tags = (version.withOcto() ? "  <span style='color:#7C6BF5'>&#9679; Octo</span>" : "")
                        + (version.downloaded() ? "  <span style='color:#4ADE9B'>downloaded</span>" : "")
                        + (version.isRelease() ? "" : "  <span style='color:#6C7484'>" + version.type() + "</span>");
                JLabel cell = (JLabel) super.getListCellRendererComponent(list,
                        "<html><b>" + version.id() + "</b>" + tags + "</html>", index, selected, focus);
                cell.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
                cell.setBackground(selected ? Theme.SURFACE_HOVER : Theme.SURFACE);
                cell.setForeground(Theme.TEXT);
                return cell;
            }
        });
        card.add(scroll(versionList), BorderLayout.CENTER);

        JPanel actions = FlatControls.row();
        FlatButton install = new FlatButton("Install Octo", FlatButton.Kind.PRIMARY);
        install.addActionListener(event -> {
            Versions.Version version = versionList.getSelectedValue();

            if (version != null) {
                background("Installing Octo for " + version.id() + "...",
                        () -> Updates.installer(settings.gameDir(), this::log).install(version.id()), id -> {
                            say("Octo is installed for " + version.id() + ": choose \"Octo Loader " + version.id()
                                    + "\" in the Minecraft Launcher, or press Play here.", Theme.SUCCESS);
                            settings.selectedVersion = version.id();
                            saveSettings();
                            refreshVersions();
                        });
            }
        });
        FlatButton remove = new FlatButton("Remove Octo", FlatButton.Kind.DANGER);
        remove.addActionListener(event -> {
            Versions.Version version = versionList.getSelectedValue();

            if (version != null && version.withOcto() && confirm("Remove the Octo installation for "
                    + version.id() + "? Your worlds and mods are not touched.")) {
                background("Removing...", () -> {
                    new ClientInstaller(settings.gameDir(), Installer.VERSION, this::log).uninstall(version.id());
                    return version.id();
                }, id -> {
                    say("Removed Octo for " + id + ".", Theme.TEXT_MUTED);
                    refreshVersions();
                });
            }
        });
        JCheckBox snapshots = checkbox("Show snapshots", settings.showSnapshots, value -> {
            settings.showSnapshots = value;
            saveSettings();
            refreshVersions();
        });
        actions.add(install);
        actions.add(remove);
        actions.add(Box.createHorizontalStrut(12));
        actions.add(snapshots);
        card.add(actions, BorderLayout.SOUTH);
        page.add(card, BorderLayout.CENTER);
        return page;
    }

    private void refreshVersions() {
        background(null, () -> Versions.list(settings.gameDir(), settings.showSnapshots), list -> {
            versionModel.clear();
            list.forEach(versionModel::addElement);
            List<String> choices = new ArrayList<>();
            list.stream().filter(Versions.Version::withOcto).forEach(version -> choices.add(version.id()));
            list.stream().filter(version -> version.isRelease() && !version.withOcto()).limit(12)
                    .forEach(version -> choices.add(version.id()));
            String keep = settings.selectedVersion;
            playVersion.removeAllItems();
            choices.forEach(playVersion::addItem);

            if (!keep.isBlank()) {
                if (!choices.contains(keep)) {
                    playVersion.insertItemAt(keep, 0);
                }

                playVersion.setSelectedItem(keep);
            }
        });
    }

    // ------------------------------------------------------------- Worlds

    private JComponent worldsPage() {
        JPanel page = page("Worlds", "Back up, restore, and see what Octo's world guard found. Octo also backs a "
                + "world up by itself when its mods change or go missing.");
        JPanel split = new JPanel(new GridLayout(1, 2, 14, 0));
        split.setOpaque(false);

        JPanel left = padded(FlatControls.card());
        FlatControls.styleList(worldList);
        worldList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        worldList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                    boolean focus) {
                Worlds.World world = (Worlds.World) value;
                String warning = world.needsAttention()
                        ? "<br><span style='color:#FF6B6B'>&#9888; missing: " + world.missingMods() + "</span>" : "";
                JLabel cell = (JLabel) super.getListCellRendererComponent(list, "<html><b>" + world.name()
                        + "</b><br><span style='color:#98A0B0'>played " + WHEN.format(world.lastPlayed()) + " &middot; "
                        + world.backups() + " backups</span>" + warning + "</html>", index, selected, focus);
                cell.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
                cell.setBackground(selected ? Theme.SURFACE_HOVER : Theme.SURFACE);
                cell.setForeground(Theme.TEXT);
                return cell;
            }
        });
        worldList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                showBackups();
            }
        });
        left.add(scroll(worldList), BorderLayout.CENTER);
        JPanel worldActions = FlatControls.row();
        FlatButton backupNow = new FlatButton("Back up now", FlatButton.Kind.PRIMARY);
        backupNow.addActionListener(event -> {
            Worlds.World world = worldList.getSelectedValue();

            if (world != null) {
                background("Backing up " + world.name() + "...",
                        () -> WorldBackups.create(world.folder(), WorldBackups.MANUAL), made -> {
                            say("Backed up " + world.name() + " (" + made.size() / 1024 + " KB).", Theme.SUCCESS);
                            refreshWorlds();
                        });
            }
        });
        FlatButton openFolder = new FlatButton("Open backups", FlatButton.Kind.SECONDARY);
        openFolder.addActionListener(event -> {
            Worlds.World world = worldList.getSelectedValue();

            if (world != null) {
                openFolder(WorldBackups.folderFor(world.folder()));
            }
        });
        worldActions.add(backupNow);
        worldActions.add(openFolder);
        left.add(worldActions, BorderLayout.SOUTH);

        JPanel right = padded(FlatControls.card());
        FlatControls.styleList(backupList);
        backupList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                    boolean focus) {
                WorldBackups.Backup backup = (WorldBackups.Backup) value;
                JLabel cell = (JLabel) super.getListCellRendererComponent(list, "<html><b>"
                        + WHEN.format(backup.time()) + "</b><br><span style='color:#98A0B0'>" + describe(backup.reason())
                        + " &middot; " + Math.max(1, backup.size() / 1024) + " KB</span></html>", index, selected, focus);
                cell.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
                cell.setBackground(selected ? Theme.SURFACE_HOVER : Theme.SURFACE);
                cell.setForeground(Theme.TEXT);
                return cell;
            }
        });
        right.add(worldDetail, BorderLayout.NORTH);
        right.add(scroll(backupList), BorderLayout.CENTER);
        JPanel backupActions = FlatControls.row();
        FlatButton restore = new FlatButton("Restore", FlatButton.Kind.SECONDARY);
        restore.addActionListener(event -> restoreSelected());
        acceptRemoval.addActionListener(event -> acceptMissing());
        backupActions.add(restore);
        backupActions.add(acceptRemoval);
        right.add(backupActions, BorderLayout.SOUTH);

        split.add(left);
        split.add(right);
        page.add(split, BorderLayout.CENTER);
        return page;
    }

    private static String describe(String reason) {
        return switch (reason) {
            case WorldBackups.MANUAL -> "made by you";
            case WorldBackups.MODS_CHANGED -> "before opening with changed mods";
            case WorldBackups.MISSING_CONTENT -> "before opening with mods missing";
            case WorldBackups.BEFORE_RESTORE -> "the world as it was before a restore";
            case WorldBackups.BEFORE_LAUNCH -> "before a launch";
            case "first-octo-open" -> "before its first time with Octo";
            default -> reason;
        };
    }

    private void refreshWorlds() {
        Worlds.World keep = worldList.getSelectedValue();
        background(null, () -> Worlds.list(settings.gameDir()), list -> {
            worldModel.clear();
            list.forEach(worldModel::addElement);

            if (!list.isEmpty()) {
                int index = 0;

                for (int i = 0; i < list.size(); i++) {
                    if (keep != null && list.get(i).folder().equals(keep.folder())) {
                        index = i;
                    }
                }

                worldList.setSelectedIndex(index);
            }

            Worlds.World last = list.isEmpty() ? null : list.get(0);
            playWorld.setText(last == null ? "No worlds yet."
                    : "<html>Last played: <b>" + last.name() + "</b>" + (settings.backupBeforeLaunch
                            ? " &mdash; backed up before each launch" : "") + (last.needsAttention()
                                    ? "<br><span style='color:#FF6B6B'>&#9888; It is missing content from "
                                            + last.missingMods() + "; see Worlds.</span>" : "") + "</html>");
            showBackups();
        });
    }

    private void showBackups() {
        Worlds.World world = worldList.getSelectedValue();
        backupModel.clear();
        acceptRemoval.setVisible(world != null && world.needsAttention());

        if (world == null) {
            worldDetail.setText(" ");
            return;
        }

        worldDetail.setText("<html>" + (world.needsAttention()
                ? "<span style='color:#FF6B6B'>Octo is protecting chunks that use " + world.missingMods()
                        + ". Put the mod back to get them back, or accept its removal.</span>"
                : world.mods().isEmpty() ? "Not opened with Octo yet."
                        : "Mods: " + String.join(", ", world.mods())) + "<br>&nbsp;</html>");
        WorldBackups.list(WorldBackups.folderFor(world.folder())).forEach(backupModel::addElement);
    }

    private void restoreSelected() {
        Worlds.World world = worldList.getSelectedValue();
        WorldBackups.Backup backup = backupList.getSelectedValue();

        if (world == null || backup == null) {
            say("Choose a world and one of its backups.", Theme.TEXT_MUTED);
            return;
        }

        if (!confirm("Put " + world.name() + " back as it was on " + WHEN.format(backup.time())
                + "?\nThe world as it is now is backed up first.")) {
            return;
        }

        background("Restoring " + world.name() + "...", () -> WorldBackups.restore(backup, world.folder()),
                before -> {
                    say("Restored " + world.name() + "." + (before == null ? ""
                            : " The world as it was is kept as a backup too."), Theme.SUCCESS);
                    refreshWorlds();
                });
    }

    private void acceptMissing() {
        Worlds.World world = worldList.getSelectedValue();

        if (world == null || !world.needsAttention()) {
            return;
        }

        if (!confirm("Accept that " + world.missingMods() + " " + (world.missingMods().size() == 1 ? "is" : "are")
                + " gone from " + world.name() + "?\n\nOcto stops protecting their blocks, items and entities, and "
                + "they are removed the next time those chunks save. Backups are kept.")) {
            return;
        }

        background("Saving...", () -> {
            WorldContent.accept(world.folder(), world.missingMods());
            return world.name();
        }, name -> {
            say("Accepted. Open " + name + " once more and the removed content is dropped.", Theme.TEXT_MUTED);
            refreshWorlds();
        });
    }

    // ----------------------------------------------------------- Settings

    private JComponent settingsPage() {
        JPanel page = page("Settings", "Kept in " + LauncherSettings.home() + ".");
        JPanel card = padded(FlatControls.card());
        JPanel body = FlatControls.column();

        JTextField gameDir = FlatControls.field(settings.gameDirectory);
        body.add(labelled("Minecraft folder", withBrowse(gameDir, true)));
        JTextField official = FlatControls.field(settings.officialLauncher);
        Path found = OfficialLauncher.find(settings.officialLauncher);
        body.add(labelled("Minecraft Launcher program (found: " + (found == null ? "not found" : found) + ")",
                withBrowse(official, false)));
        body.add(Box.createVerticalStrut(10));
        body.add(checkbox("Check for updates when Octo Launcher opens", settings.checkForUpdates,
                value -> settings.checkForUpdates = value));
        body.add(checkbox("Install a newer loader into every Octo installation automatically",
                settings.updateLoaderAutomatically, value -> settings.updateLoaderAutomatically = value));
        body.add(checkbox("Back up the last played world each time I press Play", settings.backupBeforeLaunch,
                value -> settings.backupBeforeLaunch = value));

        JSpinner keep = new JSpinner(new SpinnerNumberModel(settings.backupsToKeep, 1, 500, 1));
        keep.setMaximumSize(new Dimension(90, 34));
        keep.setBorder(FlatControls.inputBorder());

        if (keep.getEditor() instanceof JSpinner.DefaultEditor editor) {
            editor.getTextField().setBackground(Theme.SURFACE_RAISED);
            editor.getTextField().setForeground(Theme.TEXT);
            editor.getTextField().setCaretColor(Theme.TEXT);
            editor.getTextField().setColumns(3);
        }

        JPanel keepRow = FlatControls.row();
        keepRow.add(keep);
        keepRow.add(FlatControls.caption("automatic backups kept per world (yours are never removed)"));
        keepRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(Box.createVerticalStrut(6));
        body.add(keepRow);
        body.add(Box.createVerticalStrut(16));

        JPanel actions = FlatControls.row();
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        FlatButton save = new FlatButton("Save", FlatButton.Kind.PRIMARY);
        save.addActionListener(event -> {
            settings.gameDirectory = gameDir.getText().strip();
            settings.officialLauncher = official.getText().strip();
            settings.backupsToKeep = (Integer) keep.getValue();
            saveSettings();
            say("Saved.", Theme.SUCCESS);
            refreshVersions();
            refreshWorlds();
        });
        FlatButton check = new FlatButton("Check for updates now", FlatButton.Kind.SECONDARY);
        check.addActionListener(event -> checkForUpdates(true));
        actions.add(save);
        actions.add(check);
        body.add(actions);
        card.add(body, BorderLayout.NORTH);
        page.add(card, BorderLayout.CENTER);
        return page;
    }

    private JComponent labelled(String label, JComponent field) {
        JPanel box = FlatControls.column();
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel text = FlatControls.fieldLabel(label);
        text.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(text);
        box.add(Box.createVerticalStrut(6));
        field.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.add(field);
        box.add(Box.createVerticalStrut(12));
        return box;
    }

    private JComponent withBrowse(JTextField field, boolean directory) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(field, BorderLayout.CENTER);
        FlatButton browse = new FlatButton("Browse", FlatButton.Kind.SECONDARY);
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(field.getText());
            chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_AND_DIRECTORIES);

            if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        row.add(browse, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        return row;
    }

    private JCheckBox checkbox(String text, boolean value, Consumer<Boolean> onChange) {
        JCheckBox box = new JCheckBox(text, value);
        box.setOpaque(false);
        box.setForeground(Theme.TEXT);
        box.setFont(Theme.ui(Font.PLAIN, 13f));
        box.setFocusPainted(false);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        box.addActionListener(event -> onChange.accept(box.isSelected()));
        return box;
    }

    // ------------------------------------------------------------ updates

    private void checkForUpdates(boolean asked) {
        background(asked ? "Checking for updates..." : null, Updates::latest, release -> {
            boolean appNewer = Updates.isNewer(release) && release.launcherUrl() != null;
            boolean loaderNewer = release.loaderUrl() != null && !release.tag().equals(Updates.loaderTag())
                    && !Launcher.build().equals(release.tag());

            if (loaderNewer && settings.updateLoaderAutomatically) {
                background("Updating the loader...", () -> {
                    Updates.downloadLoader(release);
                    return Updates.updateInstallations(settings.gameDir(), this::log);
                }, versions -> say("Loader updated to " + release.tag() + (versions.isEmpty() ? ""
                        : " for " + String.join(", ", versions)) + ".", Theme.SUCCESS));
            }

            if (appNewer) {
                pending = release;
                bannerText.setText("<html><b>A new Octo Launcher is out</b> &mdash; " + release.name()
                        + ". It restarts to update.</html>");
                banner.setVisible(true);
            } else if (asked) {
                say(Launcher.build().equals("dev") ? "This is a development build; it is not updated. Newest release: "
                        + release.tag() + "." : "Octo Launcher is up to date (" + release.tag() + ").",
                        Theme.TEXT_MUTED);
            }
        });
    }

    private void applyUpdate() {
        Updates.Release release = pending;

        if (release == null) {
            return;
        }

        background("Downloading " + release.name() + "...", () -> Updates.stageLauncher(release), staged -> {
            try {
                Updates.restartInto(staged);
                frame.dispose();
                System.exit(0);
            } catch (Exception e) {
                say("The update was downloaded but could not be put in place: " + e.getMessage(), Theme.DANGER);
            }
        });
    }

    // ------------------------------------------------------------- common

    private <T> void background(String message, Callable<T> work, Consumer<T> done) {
        if (message != null) {
            say(message, Theme.TEXT_MUTED);
        }

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

                    if (message != null) {
                        say(cause.getMessage() == null ? cause.toString() : cause.getMessage(), Theme.DANGER);
                    }
                }
            }
        }.execute();
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> say(message, Theme.TEXT_MUTED));
    }

    private void say(String message, Color colour) {
        status.setForeground(colour);
        status.setText(message);
    }

    private boolean confirm(String question) {
        return JOptionPane.showConfirmDialog(frame, question, "Octo Launcher", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private void saveSettings() {
        try {
            settings.save();
        } catch (Exception e) {
            say("Settings could not be saved: " + e.getMessage(), Theme.DANGER);
        }
    }

    private void openFolder(Path folder) {
        try {
            Files.createDirectories(folder);
            Desktop.getDesktop().open(folder.toFile());
        } catch (Exception e) {
            say(folder.toString(), Theme.TEXT_MUTED);
        }
    }
}
