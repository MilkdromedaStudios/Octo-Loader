package studios.milkdromeda.octo.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
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
    private final JPanel list = Widgets.column();
    private final JPanel detail = Widgets.column();
    private List<Worlds.World> worlds = List.of();
    private Worlds.World selected;

    WorldsPage(App app) {
        this.app = app;
        view.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
        JPanel head = Widgets.column();
        head.add(Widgets.heading("Worlds"));
        head.add(Box.createVerticalStrut(4));
        head.add(Widgets.text("Octo backs a world up by itself when its mods change or go missing, and keeps "
                + "chunks it cannot read exactly as they were.", 13f, Font.PLAIN, Style.MUTED));
        view.add(head, BorderLayout.NORTH);

        JPanel split = Widgets.clear(new BorderLayout(18, 0));
        JPanel listHolder = new Widgets.Tracking();
        listHolder.add(list, BorderLayout.NORTH);
        JComponent scroller = Widgets.scroll(listHolder);
        scroller.setPreferredSize(new Dimension(380, 10));
        split.add(scroller, BorderLayout.WEST);
        JPanel detailHolder = new Widgets.Tracking();
        detailHolder.add(detail, BorderLayout.NORTH);
        split.add(Widgets.scroll(detailHolder), BorderLayout.CENTER);
        view.add(split, BorderLayout.CENTER);
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
            list.add(ModsPage.empty("No worlds yet", "Worlds in " + app.settings().gameDir().resolve("saves")
                    + " appear here."));
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
            row.add(Widgets.picture(HomePage.worldIcon(world, 46)), BorderLayout.WEST);
            JPanel text = Widgets.column();
            text.add(Widgets.text(HomePage.shorten(world.name(), 26), 14f, Font.BOLD, Style.TEXT));
            text.add(Box.createVerticalStrut(3));
            text.add(Widgets.text(HomePage.ago(world.lastPlayed()) + "  ·  " + world.backups() + " backups", 12f,
                    Font.PLAIN, Style.FAINT));

            if (world.needsAttention()) {
                text.add(Box.createVerticalStrut(5));
                text.add(Widgets.pill("Missing " + String.join(", ", world.missingMods()), Style.AMBER));
            }

            row.add(text, BorderLayout.CENTER);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, world.needsAttention() ? 92 : 70));
            list.add(row);
            list.add(Box.createVerticalStrut(8));
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
        summary.setLayout(new BorderLayout(16, 0));
        summary.add(Widgets.picture(HomePage.worldIcon(world, 64)), BorderLayout.WEST);
        JPanel text = Widgets.column();
        text.add(Widgets.text(world.name(), 18f, Font.BOLD, Style.TEXT));
        text.add(Box.createVerticalStrut(4));
        text.add(Widgets.text("Last played " + WHEN.format(world.lastPlayed()), 12.5f, Font.PLAIN, Style.MUTED));
        text.add(Box.createVerticalStrut(4));
        text.add(Widgets.wrapped(world.mods().isEmpty() ? "Not opened with Octo yet."
                : "Mods: " + HomePage.escape(String.join(", ", world.mods())), 12.5f, Style.FAINT, 420));
        summary.add(text, BorderLayout.CENTER);
        JPanel actions = Widgets.column();
        Widgets.Button backupNow = Widgets.button("Back up now", Icons.Glyph.BACKUP, Widgets.Kind.PRIMARY);
        backupNow.addActionListener(event -> {
            backupNow.setEnabled(false);
            app.run(() -> WorldBackups.create(world.folder(), WorldBackups.MANUAL), made -> {
                app.toast("Backed up " + world.name() + " (" + Math.max(1, made.size() / 1024) + " KB).",
                        Style.GREEN);
                app.changed();
            });
        });
        Widgets.Button folder = Widgets.button("Backups folder", Icons.Glyph.FOLDER, Widgets.Kind.SECONDARY);
        folder.addActionListener(event -> ModsPage.open(WorldBackups.folderFor(world.folder())));
        backupNow.setAlignmentX(Component.RIGHT_ALIGNMENT);
        folder.setAlignmentX(Component.RIGHT_ALIGNMENT);
        actions.add(backupNow);
        actions.add(Box.createVerticalStrut(8));
        actions.add(folder);
        summary.add(actions, BorderLayout.EAST);
        summary.setAlignmentX(Component.LEFT_ALIGNMENT);
        detail.add(summary);
        detail.add(Box.createVerticalStrut(14));

        if (world.needsAttention()) {
            Widgets.Card warning = new Widgets.Card(Style.alpha(Style.AMBER, 26), 14, false);
            warning.setLayout(new BorderLayout(12, 0));
            warning.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
            JLabel icon = new JLabel(Icons.of(Icons.Glyph.ALERT, 22, Style.AMBER));
            icon.setVerticalAlignment(JLabel.TOP);
            warning.add(icon, BorderLayout.WEST);
            warning.add(Widgets.wrapped("<b>Octo is protecting this world.</b> It uses content from "
                    + HomePage.escape(String.join(", ", world.missingMods())) + ", which is not installed now. "
                    + "Chunks with it are kept exactly as they were: put the mod back and everything returns. "
                    + "Removed it on purpose? Accept the removal and its content is dropped.", 12.5f, Style.TEXT,
                    340), BorderLayout.CENTER);
            Widgets.Button accept = Widgets.button("Accept removal", null, Widgets.Kind.DANGER);
            accept.addActionListener(event -> acceptRemoval(world));
            JPanel holder = Widgets.clear(new BorderLayout());
            holder.add(accept, BorderLayout.NORTH);
            warning.add(holder, BorderLayout.EAST);
            warning.setAlignmentX(Component.LEFT_ALIGNMENT);
            detail.add(warning);
            detail.add(Box.createVerticalStrut(14));
        }

        JLabel section = Widgets.section("Backups");
        section.setAlignmentX(Component.LEFT_ALIGNMENT);
        detail.add(section);
        List<WorldBackups.Backup> backups = WorldBackups.list(WorldBackups.folderFor(world.folder()));

        if (backups.isEmpty()) {
            detail.add(ModsPage.empty("No backups yet", "Press Back up now, or play: Octo backs a world up before "
                    + "each launch and whenever its mods change."));
        }

        for (WorldBackups.Backup backup : backups) {
            Widgets.Card row = new Widgets.Card(Style.PANEL, 12, true);
            row.setLayout(new BorderLayout(12, 0));
            row.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 12));
            row.add(new JLabel(Icons.of(backup.isAutomatic() ? Icons.Glyph.REFRESH : Icons.Glyph.BACKUP, 18,
                    backup.isAutomatic() ? Style.MUTED : Style.CYAN)), BorderLayout.WEST);
            JPanel info = Widgets.column();
            info.add(Widgets.text(WHEN.format(backup.time()), 13.5f, Font.BOLD, Style.TEXT));
            info.add(Box.createVerticalStrut(2));
            info.add(Widgets.text(describe(backup.reason()) + "  ·  " + size(backup.size()), 12f, Font.PLAIN,
                    Style.FAINT));
            row.add(info, BorderLayout.CENTER);
            Widgets.Button restore = Widgets.button("Restore", Icons.Glyph.RESTORE, Widgets.Kind.SECONDARY);
            restore.addActionListener(event -> restore(world, backup));
            JPanel holder = Widgets.clear(new BorderLayout());
            holder.add(restore, BorderLayout.NORTH);
            row.add(holder, BorderLayout.EAST);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 64));
            detail.add(row);
            detail.add(Box.createVerticalStrut(8));
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
            case "first-octo-open" -> "Before its first time with Octo";
            default -> reason;
        };
    }

    static String size(long bytes) {
        return bytes >= 1024 * 1024 ? String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0)
                : Math.max(1, bytes / 1024) + " KB";
    }
}
