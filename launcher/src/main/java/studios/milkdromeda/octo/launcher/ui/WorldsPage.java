package studios.milkdromeda.octo.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;

import studios.milkdromeda.octo.common.WorldBackups;
import studios.milkdromeda.octo.common.WorldContent;
import studios.milkdromeda.octo.launcher.Worlds;

/** Worlds: each with its backups, what the world guard found, and restoring one. */
final class WorldsPage implements App.Page {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault());

    private final App app;
    private final JPanel view = Widgets.clear(new BorderLayout(0, 18));
    private final JPanel list = Widgets.stack(8);
    private final JPanel detail = Widgets.stack(14);
    private List<Worlds.World> worlds = List.of();
    private Worlds.World selected;

    WorldsPage(App app) {
        this.app = app;
        view.setBorder(BorderFactory.createEmptyBorder(22, 24, 0, 24));
        JPanel head = Widgets.stack(4);
        head.add(Widgets.heading("Worlds"));
        head.add(Widgets.line("Octo backs a world up by itself when its mods change or go missing, and keeps chunks "
                + "it cannot read exactly as they were.", Style.font(Font.PLAIN, 13f), Style.MUTED));
        view.add(head, BorderLayout.NORTH);

        JPanel split = Widgets.clear(new BorderLayout(18, 0));
        list.setBorder(BorderFactory.createEmptyBorder(0, 0, 22, 4));
        JComponent left = Widgets.page(list);
        left.setPreferredSize(new Dimension(340, 10));
        split.add(left, BorderLayout.WEST);
        detail.setBorder(BorderFactory.createEmptyBorder(0, 0, 22, 4));
        split.add(Widgets.page(detail), BorderLayout.CENTER);
        view.add(split, BorderLayout.CENTER);

        for (int i = 0; i < 3; i++) {
            list.add(new Skeleton(70, true));
        }
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void refresh() {
        app.run(() -> Worlds.list(app.settings().gameDir()), found -> {
            worlds = found;

            if (selected != null) {
                selected = found.stream().filter(world -> world.folder().equals(selected.folder())).findFirst()
                        .orElse(null);
            }

            if (selected == null && !found.isEmpty()) {
                selected = found.get(0);
            }

            showList();
            showDetail();
        });
    }

    private void showList() {
        list.removeAll();

        if (worlds.isEmpty()) {
            list.add(ModsPage.empty("No worlds yet", "Worlds in your Minecraft folder's saves folder appear here."));
        }

        for (Worlds.World world : worlds) {
            boolean isSelected = selected != null && world.folder().equals(selected.folder());
            Widgets.Card row = new Widgets.Card(isSelected ? Style.RAISED : Style.PANEL, 14, true).hoverable(() -> {
                selected = world;
                showList();
                showDetail();
            });
            row.setLayout(new BorderLayout(12, 0));
            row.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 12));
            JPanel iconHolder = Widgets.clear(new BorderLayout());
            iconHolder.add(Widgets.picture(HomePage.worldIcon(world, 46)), BorderLayout.NORTH);
            row.add(iconHolder, BorderLayout.WEST);
            JPanel text = Widgets.stack(3);
            text.add(Widgets.line(world.name(), Style.font(Font.BOLD, 14.5f), Style.TEXT));
            text.add(Widgets.line(HomePage.ago(world.lastPlayed()) + "  ·  " + world.backups() + " backups",
                    Style.font(Font.PLAIN, 12f), Style.FAINT));

            if (world.needsAttention()) {
                JPanel pills = Widgets.flow(6, 6);
                pills.add(Widgets.pill("Missing " + String.join(", ", world.missingMods()), Style.AMBER));
                text.add(pills);
            }

            row.add(text, BorderLayout.CENTER);
            list.add(row);
        }

        list.revalidate();
        list.repaint();
    }

    private void showDetail() {
        detail.removeAll();
        Worlds.World world = selected;

        if (world == null) {
            detail.revalidate();
            detail.repaint();
            return;
        }

        Widgets.Card summary = Widgets.card();
        summary.setLayout(new BorderLayout(18, 0));
        JPanel iconHolder = Widgets.clear(new BorderLayout());
        iconHolder.add(Widgets.picture(HomePage.worldIcon(world, 68)), BorderLayout.NORTH);
        summary.add(iconHolder, BorderLayout.WEST);
        JPanel text = Widgets.stack(5);
        JLabel name = new JLabel(world.name());
        name.setFont(Style.display(22f));
        name.setForeground(Style.TEXT);
        text.add(name);
        text.add(Widgets.line("Last played " + WHEN.format(world.lastPlayed()), Style.font(Font.PLAIN, 12.5f),
                Style.MUTED));
        text.add(Widgets.paragraph(world.mods().isEmpty() ? "Not opened with Octo yet."
                : "Mods: " + String.join(", ", world.mods()), 12.5f, Style.FAINT, 2));
        JPanel actions = Widgets.flow(8, 8);
        Widgets.Button backupNow = Widgets.button("Back up now", Icons.Glyph.BACKUP, Widgets.Kind.PRIMARY);
        backupNow.addActionListener(event -> {
            backupNow.setEnabled(false);
            backupNow.setText("Backing up...");
            app.run(() -> WorldBackups.create(world.folder(), WorldBackups.MANUAL), made -> {
                app.toast("Backed up " + world.name() + " (" + size(made.size()) + ").", Style.GREEN);
                app.changed();
            });
        });
        Widgets.Button folder = Widgets.button("Backups folder", Icons.Glyph.FOLDER, Widgets.Kind.SECONDARY);
        folder.addActionListener(event -> ModsPage.open(WorldBackups.folderFor(world.folder())));
        actions.add(backupNow);
        actions.add(folder);
        text.add(actions);
        summary.add(text, BorderLayout.CENTER);
        detail.add(summary);

        if (world.needsAttention()) {
            Widgets.Card warning = new Widgets.Card(Style.mix(Style.PANEL, Style.AMBER, 0.10f), 14, false);
            warning.setLayout(new BorderLayout(14, 0));
            warning.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
            JLabel icon = new JLabel(Icons.of(Icons.Glyph.ALERT, 22, Style.AMBER));
            icon.setVerticalAlignment(JLabel.TOP);
            warning.add(icon, BorderLayout.WEST);
            JPanel words = Widgets.stack(6);
            words.add(Widgets.line("Octo is protecting this world", Style.font(Font.BOLD, 14f), Style.TEXT));
            words.add(Widgets.paragraph("It uses content from " + String.join(", ", world.missingMods())
                    + ", which is not installed now. Chunks with it are kept exactly as they were: put the mod back "
                    + "and everything returns. Removed it on purpose? Accept the removal and its content is dropped.",
                    13f, Style.MUTED, 0));
            JPanel accept = Widgets.flow(8, 8);
            Widgets.Button acceptButton = Widgets.button("Accept removal", null, Widgets.Kind.DANGER);
            acceptButton.addActionListener(event -> acceptRemoval(world));
            accept.add(acceptButton);
            words.add(accept);
            warning.add(words, BorderLayout.CENTER);
            detail.add(warning);
        }

        JLabel section = Widgets.section("Backups");
        section.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 0));
        detail.add(section);
        List<WorldBackups.Backup> backups = WorldBackups.list(WorldBackups.folderFor(world.folder()));

        if (backups.isEmpty()) {
            detail.add(ModsPage.empty("No backups yet", "Press Back up now, or play: Octo backs a world up before "
                    + "each launch and whenever its mods change."));
        }

        for (WorldBackups.Backup backup : backups) {
            Widgets.Card row = new Widgets.Card(Style.PANEL, 12, true);
            row.setLayout(new BorderLayout(14, 0));
            row.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 14));
            JLabel kind = new JLabel(Icons.of(backup.isAutomatic() ? Icons.Glyph.REFRESH : Icons.Glyph.BACKUP, 18,
                    backup.isAutomatic() ? Style.MUTED : Style.CYAN));
            kind.setVerticalAlignment(JLabel.TOP);
            kind.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
            row.add(kind, BorderLayout.WEST);
            JPanel info = Widgets.stack(3);
            info.add(Widgets.line(WHEN.format(backup.time()), Style.font(Font.BOLD, 13.5f), Style.TEXT));
            info.add(Widgets.line(describe(backup.reason()) + "   ·   " + size(backup.size()),
                    Style.font(Font.PLAIN, 12f), Style.FAINT));
            row.add(info, BorderLayout.CENTER);
            Widgets.Button restore = Widgets.button("Restore", Icons.Glyph.RESTORE, Widgets.Kind.SECONDARY);
            restore.addActionListener(event -> restore(world, backup));
            JPanel holder = Widgets.clear(new BorderLayout());
            holder.add(restore, BorderLayout.NORTH);
            row.add(holder, BorderLayout.EAST);
            detail.add(row);
        }

        detail.revalidate();
        detail.repaint();
    }

    private void restore(Worlds.World world, WorldBackups.Backup backup) {
        if (JOptionPane.showConfirmDialog(app.frame(), "Put " + world.name() + " back as it was on "
                + WHEN.format(backup.time()) + "?\nThe world as it is now is backed up first.", "Restore world",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }

        app.run(() -> WorldBackups.restore(backup, world.folder()), before -> {
            app.toast("Restored " + world.name() + "." + (before == null ? ""
                    : " The world as it was is kept as a backup too."), Style.GREEN);
            app.changed();
        });
    }

    private void acceptRemoval(Worlds.World world) {
        if (JOptionPane.showConfirmDialog(app.frame(), "Accept that " + String.join(", ", world.missingMods())
                + " is gone from " + world.name() + "?\n\nOcto stops protecting its blocks, items and entities, and "
                + "they are removed the next time those chunks save. Backups are kept.", "Accept removal",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }

        app.run(() -> {
            WorldContent.accept(world.folder(), world.missingMods());
            return world;
        }, done -> {
            app.toast("Accepted. Open " + world.name() + " once more and the removed content is dropped.",
                    Style.MUTED);
            app.changed();
        });
    }

    static String describe(String reason) {
        return switch (reason) {
            case WorldBackups.MANUAL -> "Made by you";
            case WorldBackups.MODS_CHANGED -> "Before opening with changed mods";
            case WorldBackups.MISSING_CONTENT -> "Before opening with mods missing";
            case WorldBackups.BEFORE_RESTORE -> "The world before a restore";
            case WorldBackups.BEFORE_LAUNCH -> "Before a launch";
            case WorldBackups.BEFORE_SESSION -> "Before playing it";
            case WorldBackups.LAST_GOOD -> "Last good copy, before a session that crashed";
            case "first-octo-open" -> "Before its first time with Octo";
            default -> reason;
        };
    }

    static String size(long bytes) {
        return bytes >= 1024 * 1024 ? String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
                : Math.max(1, bytes / 1024) + " KB";
    }
}
