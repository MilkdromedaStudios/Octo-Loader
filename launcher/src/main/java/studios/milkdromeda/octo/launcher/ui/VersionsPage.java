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
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import studios.milkdromeda.octo.installer.ClientInstaller;
import studios.milkdromeda.octo.installer.Installer;
import studios.milkdromeda.octo.launcher.Updates;
import studios.milkdromeda.octo.launcher.Versions;

/** Versions: Octo installed for whichever Minecraft versions the player wants. */
final class VersionsPage implements App.Page {
    private final App app;
    private final JPanel view = Widgets.clear(new BorderLayout(0, 18));
    private final JPanel list = Widgets.column();
    private final Widgets.Field filter = new Widgets.Field("Find a version", Icons.Glyph.SEARCH);
    private final Widgets.Switch snapshots = new Widgets.Switch(false);
    private List<Versions.Version> versions = List.of();

    VersionsPage(App app) {
        this.app = app;
        view.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));
        JPanel head = Widgets.clear(new BorderLayout());
        JPanel title = Widgets.column();
        title.add(Widgets.heading("Versions"));
        title.add(Box.createVerticalStrut(4));
        title.add(Widgets.text("Install Octo for any Minecraft version. The Minecraft Launcher downloads the game "
                + "the first time you play it.", 13f, Font.PLAIN, Style.MUTED));
        head.add(title, BorderLayout.WEST);
        view.add(head, BorderLayout.NORTH);

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
        toggle.add(snapshots);
        toggle.add(Widgets.text("Snapshots", 13f, Font.PLAIN, Style.MUTED));
        bar.add(toggle, BorderLayout.EAST);
        body.add(bar, BorderLayout.NORTH);
        JPanel holder = new Widgets.Tracking();
        holder.add(list, BorderLayout.NORTH);
        body.add(Widgets.scroll(holder), BorderLayout.CENTER);
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
            list.add(Box.createVerticalStrut(8));
        }

        list.revalidate();
        list.repaint();
    }

    private JComponent row(Versions.Version version) {
        boolean selected = version.id().equals(app.selectedVersion());
        Widgets.Card row = new Widgets.Card(selected ? Style.RAISED : Style.PANEL, 14, true);
        row.setLayout(new BorderLayout(14, 0));
        row.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 12));
        row.add(new javax.swing.JLabel(Icons.of(Icons.Glyph.VERSIONS, 22, version.withOcto() ? Style.VIOLET
                : Style.FAINT)), BorderLayout.WEST);
        JPanel text = Widgets.row(8);
        text.add(Widgets.text("Minecraft " + version.id(), 14.5f, Font.BOLD, Style.TEXT));

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
        middle.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        middle.add(text, BorderLayout.NORTH);
        row.add(middle, BorderLayout.CENTER);

        JPanel actions = Widgets.row(8);

        if (version.withOcto()) {
            Widgets.Button choose = Widgets.button(selected ? "Selected" : "Select", Icons.Glyph.CHECK,
                    Widgets.Kind.SECONDARY);
            choose.setEnabled(!selected);
            choose.addActionListener(event -> {
                app.settings().selectedVersion = version.id();
                app.save();
                app.changed();
            });
            actions.add(choose);
            Widgets.Button remove = Widgets.iconButton(Icons.Glyph.TRASH, "Remove Octo for " + version.id(),
                    Widgets.Kind.DANGER);
            remove.addActionListener(event -> {
                if (JOptionPane.showConfirmDialog(app.frame(), "Remove the Octo installation for " + version.id()
                        + "?\nYour worlds and mods are not touched.", "Remove", JOptionPane.OK_CANCEL_OPTION)
                        == JOptionPane.OK_OPTION) {
                    app.run(() -> {
                        new ClientInstaller(app.settings().gameDir(), Installer.VERSION, message -> { })
                                .uninstall(version.id());
                        return version.id();
                    }, id -> {
                        app.toast("Removed Octo for " + id + ".", Style.MUTED);
                        app.changed();
                    });
                }
            });
            actions.add(remove);
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

        row.add(actions, BorderLayout.EAST);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        return row;
    }
}
