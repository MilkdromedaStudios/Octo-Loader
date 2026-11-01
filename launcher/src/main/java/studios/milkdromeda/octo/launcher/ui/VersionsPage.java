package studios.milkdromeda.octo.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import studios.milkdromeda.octo.launcher.Play;
import studios.milkdromeda.octo.launcher.Updates;
import studios.milkdromeda.octo.launcher.Versions;
import studios.milkdromeda.octo.launcher.game.GameFiles;

/** Versions: Octo installed for whichever Minecraft versions the player wants. */
final class VersionsPage implements App.Page {
    private final App app;
    private final JPanel view = Widgets.clear(new BorderLayout(0, 18));
    private final JPanel list = Widgets.stack(8);
    private final Widgets.Field filter = new Widgets.Field("Find a version", Icons.Glyph.SEARCH);
    private final Widgets.Switch snapshots = new Widgets.Switch(false);
    private List<Versions.Version> versions = List.of();

    VersionsPage(App app) {
        this.app = app;
        view.setBorder(BorderFactory.createEmptyBorder(22, 24, 0, 24));
        JPanel title = Widgets.stack(4);
        title.add(Widgets.heading("Versions"));
        title.add(Widgets.line("Any Minecraft version, with Octo. Downloaded once, checked against Mojang's "
                + "checksums, and ready to play.", Style.font(Font.PLAIN, 13f), Style.MUTED));
        view.add(title, BorderLayout.NORTH);

        JPanel body = Widgets.clear(new BorderLayout(0, 14));
        JPanel bar = Widgets.clear(new BorderLayout(12, 0));
        bar.add(filter, BorderLayout.CENTER);
        JPanel toggle = Widgets.row(8);
        snapshots.set(app.settings().showSnapshots, false);
        snapshots.onChange(value -> {
            app.settings().showSnapshots = value;
            app.save();
            refresh();
        });
        JPanel switchHolder = Widgets.clear(new BorderLayout());
        switchHolder.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        switchHolder.add(snapshots, BorderLayout.NORTH);
        toggle.add(switchHolder);
        JLabel snapshotLabel = Widgets.text("Snapshots", 13f, Font.PLAIN, Style.MUTED);
        snapshotLabel.setBorder(BorderFactory.createEmptyBorder(9, 0, 0, 0));
        toggle.add(snapshotLabel);
        bar.add(toggle, BorderLayout.EAST);
        body.add(bar, BorderLayout.NORTH);
        list.setBorder(BorderFactory.createEmptyBorder(0, 0, 22, 4));
        body.add(Widgets.page(list), BorderLayout.CENTER);
        view.add(body, BorderLayout.CENTER);

        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                show();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                show();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                show();
            }
        });
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void refresh() {
        app.run(() -> Versions.list(app.settings().gameDir(), app.settings().showSnapshots), found -> {
            versions = found;
            show();
        });
    }

    private void show() {
        list.removeAll();
        String query = filter.getText().strip().toLowerCase(Locale.ROOT);
        // Installed first, then the rest as Mojang lists them.
        List<Versions.Version> sorted = new java.util.ArrayList<>(versions.stream().filter(Versions.Version::withOcto)
                .toList());
        versions.stream().filter(version -> !version.withOcto()).forEach(sorted::add);
        int shown = 0;

        for (Versions.Version version : sorted) {
            if (!query.isEmpty() && !version.id().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }

            if (shown++ > 80) {
                break;
            }

            list.add(row(version));
        }

        list.revalidate();
        list.repaint();
    }

    private JComponent row(Versions.Version version) {
        boolean selected = version.id().equals(app.selectedVersion());
        Widgets.Card row = new Widgets.Card(selected ? Style.RAISED : Style.PANEL, 14, true);
        row.setLayout(new BorderLayout(14, 0));
        row.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 14));
        javax.swing.JLabel glyph = new javax.swing.JLabel(Icons.of(Icons.Glyph.VERSIONS, 22, version.withOcto()
                ? Style.VIOLET : Style.FAINT));
        glyph.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        glyph.setVerticalAlignment(javax.swing.JLabel.TOP);
        row.add(glyph, BorderLayout.WEST);
        JPanel text = Widgets.flow(8, 6);
        text.add(Widgets.text("Minecraft " + version.id(), 15f, Font.BOLD, Style.TEXT));

        if (version.withOcto()) {
            text.add(Widgets.pill("Octo", Style.VIOLET));
        }

        if (version.downloaded()) {
            text.add(Widgets.pill("Downloaded", Style.GREEN));
        }

        if (!version.isRelease()) {
            text.add(Widgets.pill(version.type(), Style.AMBER));
        }

        if (selected) {
            text.add(Widgets.pill("Selected", Style.CYAN));
        }

        JPanel middle = Widgets.clear(new BorderLayout());
        middle.setBorder(BorderFactory.createEmptyBorder(7, 0, 0, 0));
        middle.add(text, BorderLayout.NORTH);
        row.add(middle, BorderLayout.CENTER);

        JPanel actions = Widgets.row(8);
        boolean here = version.withOcto() || version.downloaded();

        if (here) {
            Widgets.Button choose = Widgets.button(selected ? "Selected" : "Select", Icons.Glyph.CHECK,
                    Widgets.Kind.SECONDARY);
            choose.setEnabled(!selected);
            choose.addActionListener(event -> {
                app.settings().selectedVersion = version.id();
                app.save();
                app.changed();
            });
            actions.add(choose);
            Widgets.Button remove = Widgets.iconButton(Icons.Glyph.TRASH, "Delete Minecraft " + version.id(),
                    Widgets.Kind.DANGER);
            remove.addActionListener(event -> {
                if (JOptionPane.showConfirmDialog(app.frame(), "Delete Minecraft " + version.id() + "?\nIts game "
                        + "files and Octo's installation for it go. Your worlds and mods stay, and you can download "
                        + "it again any time.", "Delete version", JOptionPane.OK_CANCEL_OPTION)
                        == JOptionPane.OK_OPTION) {
                    app.run(() -> {
                        Play.deleteVersion(app.settings(), version.id());
                        return version.id();
                    }, id -> {
                        app.toast("Deleted Minecraft " + id + ".", Style.MUTED);
                        app.changed();
                    });
                }
            });
            actions.add(remove);
        } else if (Play.launchesItself(app.settings())) {
            Widgets.Button download = Widgets.button("Download", Icons.Glyph.DOWNLOAD, Widgets.Kind.SECONDARY);
            download.addActionListener(event -> {
                download.setEnabled(false);
                download.setText("Starting...");
                GameFiles.Progress progress = (stage, done, total) -> javax.swing.SwingUtilities.invokeLater(() ->
                        download.setText(total > 0 ? Math.round(done * 100.0 / total) + "%" : "Checking..."));
                app.run(() -> Play.download(app.settings(), version.id(), false, progress), id -> {
                    app.settings().selectedVersion = version.id();
                    app.save();
                    app.toast("Minecraft " + version.id() + " is downloaded, with Octo. Press Launch on Home to "
                            + "play.", Style.GREEN);
                    app.changed();
                }, failure -> {
                    download.setEnabled(true);
                    download.setText("Download");
                });
            });
            actions.add(download);
        } else {
            Widgets.Button install = Widgets.button("Install Octo", Icons.Glyph.DOWNLOAD, Widgets.Kind.PRIMARY);
            install.addActionListener(event -> {
                install.setEnabled(false);
                install.setText("Installing...");
                app.run(() -> Updates.installer(app.settings().gameDir(), message -> { }).install(version.id()),
                        id -> {
                            app.settings().selectedVersion = version.id();
                            app.save();
                            app.toast("Octo is installed for " + version.id() + ". Press Launch on Home to play.",
                                    Style.GREEN);
                            app.changed();
                        });
            });
            actions.add(install);
        }

        JPanel actionHolder = Widgets.clear(new BorderLayout());
        actionHolder.add(actions, BorderLayout.NORTH);
        row.add(actionHolder, BorderLayout.EAST);
        return row;
    }
}
