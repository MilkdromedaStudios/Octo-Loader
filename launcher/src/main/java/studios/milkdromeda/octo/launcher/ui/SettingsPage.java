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
import studios.milkdromeda.octo.launcher.Play;
import studios.milkdromeda.octo.launcher.auth.Account;

/** Settings: folders, updates, backups, and what this build is. */
final class SettingsPage implements App.Page {
    private final App app;
    private final JPanel view = Widgets.clear(new BorderLayout(0, 18));
    private final JLabel keepValue = Widgets.text(" ", 14f, Font.BOLD, Style.TEXT);
    private final JLabel memoryValue = Widgets.text(" ", 14f, Font.BOLD, Style.TEXT);
    private final JPanel accountRow = Widgets.clear(new BorderLayout(16, 0));

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

        column.add(group("Account", accountRow,
                switchRow("Play through the Minecraft Launcher instead", "Octo selects itself there, and the "
                        + "Minecraft Launcher signs you in and starts the game.", settings.useOfficialLauncher,
                        value -> {
                            settings.useOfficialLauncher = value;
                            app.save();
                            app.changed();
                        }),
                textRow("Microsoft app ID", clientIdHint(), settings.microsoftClientId,
                        buildHasClientId() ? "Set by this build" : "Not set",
                        value -> {
                            settings.microsoftClientId = value;
                            app.save();
                            app.changed();
                        })));
        column.add(group("Game", memoryRow(),
                folderRow("Java", "Empty: the Java Mojang makes for each version, downloaded by itself. Or "
                        + "choose a java program of your own.", settings.javaPath, false, value -> {
                            settings.javaPath = value;
                            app.save();
                        }),
                textRow("Java options", "Added when the game starts, as typed: for example -XX:+UseZGC.",
                        settings.jvmArguments, "None", value -> {
                            settings.jvmArguments = value;
                            app.save();
                        })));
        column.add(group("Game files", repairRow(), deleteRow()));
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
        memoryValue.setText(memory(app.settings().memoryMb));
        showAccount();
    }

    private void showAccount() {
        accountRow.removeAll();
        Account active = app.accounts().active();

        if (active == null) {
            accountRow.add(labels("Not signed in", "Sign in with the Microsoft account that has Minecraft: Java "
                    + "Edition. Octo keeps the sign-in on this computer, for your user only."), BorderLayout.CENTER);
            Widgets.Button signIn = Widgets.button("Sign in with Microsoft", Icons.Glyph.USER, Widgets.Kind.PRIMARY);
            signIn.addActionListener(event -> SignInSheet.show(app, account -> { }));
            JPanel holder = Widgets.clear(new BorderLayout());
            holder.add(signIn, BorderLayout.NORTH);
            accountRow.add(holder, BorderLayout.EAST);
        } else {
            JLabel face = new JLabel();
            Heads.load(active, 44, 10, image -> face.setIcon(new javax.swing.ImageIcon(image)));
            JPanel faceHolder = Widgets.clear(new BorderLayout());
            faceHolder.add(face, BorderLayout.NORTH);
            accountRow.add(faceHolder, BorderLayout.WEST);
            int others = app.accounts().all().size() - 1;
            accountRow.add(labels(active.name(), "Signed in with Microsoft. Minecraft: Java Edition"
                    + (others > 0 ? ". " + others + (others == 1 ? " other account" : " other accounts")
                            + " signed in too: switch in the title bar." : ".")), BorderLayout.CENTER);
            Widgets.Button out = Widgets.button("Sign out", Icons.Glyph.SIGN_OUT, Widgets.Kind.SECONDARY);
            out.addActionListener(event -> app.signOut(active));
            JPanel holder = Widgets.clear(new BorderLayout());
            holder.add(out, BorderLayout.NORTH);
            accountRow.add(holder, BorderLayout.EAST);
        }

        accountRow.revalidate();
        accountRow.repaint();
    }

    private static boolean buildHasClientId() {
        String own = Launcher.manifestValue("Octo-Msa-Client-Id");
        return own != null && !own.isBlank();
    }

    private String clientIdHint() {
        return buildHasClientId()
                ? "This build signs in with its own. Set one here only to use a different Azure app."
                : "The Azure app (client) ID Octo signs in with, approved by Mojang for Minecraft. This build has "
                        + "none of its own.";
    }

    private JComponent textRow(String title, String hint, String value, String placeholder,
            Consumer<String> onChange) {
        JPanel row = Widgets.stack(10);
        row.add(labels(title, hint));
        Widgets.Field field = new Widgets.Field(placeholder, null);
        field.setText(value);
        field.addActionListener(event -> onChange.accept(field.getText().strip()));
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                onChange.accept(field.getText().strip());
            }
        });
        row.add(field);
        return row;
    }

    private JComponent memoryRow() {
        JPanel row = Widgets.clear(new BorderLayout(16, 0));
        row.add(labels("Memory for Minecraft", "The most the game may use. 4 GB suits most mod packs; big ones "
                + "like more."), BorderLayout.CENTER);
        JPanel stepper = Widgets.row(6);
        stepper.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
        Widgets.Button less = Widgets.iconButton(Icons.Glyph.MINIMIZE, "Less", Widgets.Kind.SECONDARY);
        Widgets.Button more = Widgets.iconButton(Icons.Glyph.PLUS, "More", Widgets.Kind.SECONDARY);
        less.addActionListener(event -> changeMemory(-512));
        more.addActionListener(event -> changeMemory(512));
        stepper.add(less);
        memoryValue.setPreferredSize(new Dimension(60, 30));
        memoryValue.setHorizontalAlignment(JLabel.CENTER);
        stepper.add(memoryValue);
        stepper.add(more);
        row.add(stepper, BorderLayout.EAST);
        return row;
    }

    private void changeMemory(int change) {
        LauncherSettings settings = app.settings();
        settings.memoryMb = Math.max(1024, Math.min(32768, settings.memoryMb + change));
        memoryValue.setText(memory(settings.memoryMb));
        app.save();
    }

    static String memory(int megabytes) {
        return megabytes % 1024 == 0 ? megabytes / 1024 + " GB"
                : String.format(java.util.Locale.ROOT, "%.1f GB", megabytes / 1024.0);
    }

    private JComponent repairRow() {
        JPanel repair = Widgets.clear(new BorderLayout(16, 0));
        repair.add(labels("Repair Minecraft", "Checks every file of the version you play against Mojang's "
                + "checksums and downloads again any that are damaged or missing."), BorderLayout.CENTER);
        Widgets.Button repairButton = Widgets.button("Repair", Icons.Glyph.REFRESH, Widgets.Kind.SECONDARY);
        repairButton.addActionListener(event -> {
            repairButton.setEnabled(false);
            repairButton.setText("Checking...");
            app.run(() -> Play.download(app.settings(), app.settings().selectedVersion, true,
                    studios.milkdromeda.octo.launcher.game.GameFiles.Progress.NONE), id -> {
                        repairButton.setEnabled(true);
                        repairButton.setText("Repair");
                        app.toast("Every file is as Mojang made it. Ready to play.", Style.GREEN);
                        app.changed();
                    }, failure -> {
                        repairButton.setEnabled(true);
                        repairButton.setText("Repair");
                    });
        });
        JPanel repairHolder = Widgets.clear(new BorderLayout());
        repairHolder.add(repairButton, BorderLayout.NORTH);
        repair.add(repairHolder, BorderLayout.EAST);
        return repair;
    }

    private JComponent deleteRow() {
        JPanel delete = Widgets.clear(new BorderLayout(16, 0));
        delete.add(labels("Delete Minecraft files", "Free up space: the downloaded game, mods, packs, and if you "
                + "choose, worlds and their backups."), BorderLayout.CENTER);
        Widgets.Button deleteButton = Widgets.button("Delete files...", Icons.Glyph.TRASH, Widgets.Kind.DANGER);
        deleteButton.addActionListener(event -> DeleteSheet.show(app));
        JPanel deleteHolder = Widgets.clear(new BorderLayout());
        deleteHolder.add(deleteButton, BorderLayout.NORTH);
        delete.add(deleteHolder, BorderLayout.EAST);
        return delete;
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
                + ". Sign-in goes through Microsoft's own page; the game, its libraries and its Java come from "
                + "Mojang, each file checked against Mojang's checksums."),
                BorderLayout.CENTER);
        Widgets.Button data = Widgets.button("Data folder", Icons.Glyph.FOLDER, Widgets.Kind.SECONDARY);
        data.addActionListener(event -> ModsPage.open(LauncherSettings.home()));
        JPanel holder = Widgets.clear(new BorderLayout());
        holder.add(data, BorderLayout.NORTH);
        row.add(holder, BorderLayout.EAST);
        return row;
    }
}
