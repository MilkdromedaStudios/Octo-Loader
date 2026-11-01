package studios.milkdromeda.octo.launcher.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.nio.file.Path;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;

import studios.milkdromeda.octo.installer.Installer;
import studios.milkdromeda.octo.launcher.Launcher;
import studios.milkdromeda.octo.launcher.LauncherSettings;
import studios.milkdromeda.octo.launcher.OfficialLauncher;

/** Settings: folders, updates, backups, and what this build is. */
final class SettingsPage implements App.Page {
    private final App app;
    private final JPanel view = Widgets.clear(new BorderLayout(0, 18));
    private final JLabel keepValue = Widgets.text(" ", 14f, Font.BOLD, Style.TEXT);

    SettingsPage(App app) {
        this.app = app;
        LauncherSettings settings = app.settings();
        view.setBorder(BorderFactory.createEmptyBorder(22, 24, 0, 24));
        JPanel head = Widgets.stack(4);
        head.add(Widgets.heading("Settings"));
        head.add(Widgets.line("Saved as you change them, in " + tidy(LauncherSettings.home()) + ".",
                Style.font(Font.PLAIN, 13f), Style.MUTED));
        view.add(head, BorderLayout.NORTH);

        JPanel column = Widgets.stack(18);
        column.setBorder(BorderFactory.createEmptyBorder(0, 0, 24, 4));

        column.add(group("Minecraft", folderRow("Minecraft folder", "Where saves, mods and versions live.",
                settings.gameDirectory, true, value -> {
                    settings.gameDirectory = value;
                    app.save();
                    app.changed();
                }), folderRow("Minecraft Launcher", launcherHint(settings.officialLauncher),
                        settings.officialLauncher, false, value -> {
                            settings.officialLauncher = value;
                            app.save();
                        })));
        column.add(group("Updates",
                switchRow("Check for updates when Octo Launcher opens", "A newer launcher shows in the title bar.",
                        settings.checkForUpdates, value -> {
                            settings.checkForUpdates = value;
                            app.save();
                        }),
                switchRow("Update Octo Loader automatically", "Installed into every Octo installation as soon as "
                        + "it is out, checked against the release's checksums.", settings.updateLoaderAutomatically,
                        value -> {
                            settings.updateLoaderAutomatically = value;
                            app.save();
                        })));
        column.add(group("Backups",
                switchRow("Back up my last world each time I launch", "Whole-world zips in your Minecraft folder's "
                        + "backups folder.", settings.backupBeforeLaunch, value -> {
                            settings.backupBeforeLaunch = value;
                            app.save();
                        }),
                keepRow()));
        column.add(group("About", about()));

        view.add(Widgets.page(column), BorderLayout.CENTER);
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void refresh() {
        keepValue.setText(Integer.toString(app.settings().backupsToKeep));
    }

    private JComponent group(String title, JComponent... rows) {
        JPanel box = Widgets.stack(0);
        box.add(Widgets.section(title));
        Widgets.Card card = Widgets.card();
        JPanel inner = Widgets.stack(0);

        for (int i = 0; i < rows.length; i++) {
            inner.add(rows[i]);

            if (i < rows.length - 1) {
                JPanel rule = new JPanel() {
                    @Override
                    public Dimension getPreferredSize() {
                        return new Dimension(1, 29);
                    }

                    @Override
                    protected void paintComponent(java.awt.Graphics graphics) {
                        graphics.setColor(Style.LINE);
                        graphics.fillRect(0, 14, getWidth(), 1);
                    }
                };
                rule.setOpaque(false);
                inner.add(rule);
            }
        }

        card.add(inner, BorderLayout.CENTER);
        box.add(card);
        return box;
    }

    private static JPanel labels(String title, String hint) {
        JPanel text = Widgets.stack(4);
        text.add(Widgets.line(title, Style.font(Font.BOLD, 14f), Style.TEXT));
        text.add(Widgets.paragraph(hint, 12.5f, Style.FAINT, 0));
        return text;
    }

    /** A path with the home folder as ~, so it fits. */
    static String tidy(Path path) {
        String home = System.getProperty("user.home");
        String text = path.toString();
        return home != null && text.startsWith(home) ? "~" + text.substring(home.length()) : text;
    }

    private JComponent switchRow(String title, String hint, boolean value, Consumer<Boolean> onChange) {
        JPanel row = Widgets.clear(new BorderLayout(16, 0));
        row.add(labels(title, hint), BorderLayout.CENTER);
        Widgets.Switch toggle = new Widgets.Switch(value);
        toggle.onChange(onChange);
        JPanel holder = Widgets.clear(new BorderLayout());
        holder.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        holder.add(toggle, BorderLayout.NORTH);
        row.add(holder, BorderLayout.EAST);
        return row;
    }

    private JComponent folderRow(String title, String hint, String value, boolean directory,
            Consumer<String> onChange) {
        JPanel row = Widgets.stack(10);
        row.add(labels(title, hint));
        JPanel line = Widgets.clear(new BorderLayout(10, 0));
        Widgets.Field field = new Widgets.Field(directory ? "Folder" : "Found automatically", null);
        field.setText(value);
        field.addActionListener(event -> onChange.accept(field.getText().strip()));
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                onChange.accept(field.getText().strip());
            }
        });
        line.add(field, BorderLayout.CENTER);
        Widgets.Button browse = Widgets.button("Browse", Icons.Glyph.FOLDER, Widgets.Kind.SECONDARY);
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(field.getText());
            chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_AND_DIRECTORIES);

            if (chooser.showOpenDialog(app.frame()) == JFileChooser.APPROVE_OPTION) {
                field.setText(chooser.getSelectedFile().getAbsolutePath());
                onChange.accept(field.getText());
            }
        });
        line.add(browse, BorderLayout.EAST);
        row.add(line);
        return row;
    }

    private static String launcherHint(String configured) {
        Path found = OfficialLauncher.find(configured);
        return found == null ? "Not found where it is usually installed. On Windows, the Microsoft Store version is "
                + "opened by itself; otherwise choose it here." : "Found: " + found;
    }

    private JComponent keepRow() {
        JPanel row = Widgets.clear(new BorderLayout(16, 0));
        row.add(labels("Automatic backups to keep", "Per world. Backups you make yourself are never removed."),
                BorderLayout.CENTER);
        JPanel stepper = Widgets.row(6);
        stepper.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        Widgets.Button less = Widgets.iconButton(Icons.Glyph.MINIMIZE, "Fewer", Widgets.Kind.SECONDARY);
        Widgets.Button moreButton = Widgets.iconButton(Icons.Glyph.PLUS, "More", Widgets.Kind.SECONDARY);
        less.addActionListener(event -> keep(-1));
        moreButton.addActionListener(event -> keep(1));
        stepper.add(less);
        keepValue.setPreferredSize(new Dimension(34, 30));
        keepValue.setHorizontalAlignment(JLabel.CENTER);
        stepper.add(keepValue);
        stepper.add(moreButton);
        row.add(stepper, BorderLayout.EAST);
        return row;
    }

    private void keep(int change) {
        LauncherSettings settings = app.settings();
        settings.backupsToKeep = Math.max(1, Math.min(200, settings.backupsToKeep + change));
        keepValue.setText(Integer.toString(settings.backupsToKeep));
        app.save();
    }

    private JComponent about() {
        JPanel row = Widgets.clear(new BorderLayout(16, 0));
        row.add(labels("Octo Launcher " + Installer.VERSION, "Build " + Launcher.build()
                + ". Sign-in, downloads and the game itself are handled by the official Minecraft Launcher."),
                BorderLayout.CENTER);
        Widgets.Button data = Widgets.button("Data folder", Icons.Glyph.FOLDER, Widgets.Kind.SECONDARY);
        data.addActionListener(event -> ModsPage.open(LauncherSettings.home()));
        JPanel holder = Widgets.clear(new BorderLayout());
        holder.add(data, BorderLayout.NORTH);
        row.add(holder, BorderLayout.EAST);
        return row;
    }
}
