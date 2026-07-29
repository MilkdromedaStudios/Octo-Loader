package studios.milkdromeda.octo.installer;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The installer window.
 *
 * <p>Two panes rather than tabs, one job each: point at a Minecraft folder and
 * install, or point at a server folder and install. Everything the installer
 * did to the disk is written to the log at the bottom, because an installer
 * that quietly edits a launcher profile should be able to show its work.
 */
final class InstallerUi {
    private static final Dimension MINIMUM = new Dimension(780, 640);

    private final JFrame frame = new JFrame("Octo Loader " + Installer.VERSION);
    private final JTextArea output = new JTextArea();

    /** The one line that says how the last thing went, drawn as a chip. */
    private Color statusColour = Theme.TEXT_MUTED;
    private final JLabel status = new JLabel(" ") {
        @Override
        protected void paintComponent(Graphics graphics) {
            if (getText().isBlank()) {
                super.paintComponent(graphics);
                return;
            }

            Graphics2D canvas = Theme.smooth(graphics);
            canvas.setColor(FlatControls.mix(statusColour, Theme.BACKGROUND, 0.84f));
            canvas.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
            canvas.setColor(FlatControls.mix(statusColour, Theme.BACKGROUND, 0.58f));
            canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            canvas.setColor(statusColour);
            canvas.fillOval(11, getHeight() / 2 - 3, 6, 6);
            canvas.dispose();
            super.paintComponent(graphics);
        }
    };
    private final CardLayout pages = new CardLayout();
    private final JPanel pageHolder = new JPanel(pages);

    private final JTextField clientDirectory =
            FlatControls.field(MinecraftPaths.defaultGameDirectory().toString());
    private final JComboBox<String> clientVersion = FlatControls.comboBox();
    private final FlatButton install = new FlatButton("Install", FlatButton.Kind.PRIMARY);
    private final FlatButton uninstall = new FlatButton("Uninstall", FlatButton.Kind.DANGER);

    private final JTextField serverDirectory = FlatControls.field(Path.of("").toAbsolutePath().toString());
    private final JTextField serverJar = FlatControls.field("server.jar");
    private final FlatButton installServer = new FlatButton("Install", FlatButton.Kind.PRIMARY);

    static void show() {
        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            Theme.applyToDialogs();
        } catch (Exception e) {
            // Whatever is already installed will do; the window paints itself.
        }

        SwingUtilities.invokeLater(() -> new InstallerUi().open());
    }

    private void open() {
        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(Theme.BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(24, 28, 22, 28));

        root.add(header(), BorderLayout.NORTH);
        root.add(body(), BorderLayout.CENTER);
        root.add(logPanel(), BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setIconImages(Mark.icons());
        frame.setMinimumSize(MINIMUM);
        frame.setSize(MINIMUM);
        frame.setLocationRelativeTo(null);
        frame.getRootPane().setDefaultButton(install);
        closeOnEscape(root);
        frame.setVisible(true);

        refreshVersions();
    }

    // ------------------------------------------------------------------ chrome

    private JPanel header() {
        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setOpaque(false);

        JPanel text = FlatControls.column();
        text.add(FlatControls.title("Octo Loader"));
        text.add(javax.swing.Box.createVerticalStrut(4));
        text.add(FlatControls.caption("Fabric, Quilt, Forge, NeoForge and LiteLoader mods, side by side."));

        header.add(new Mark(44), BorderLayout.WEST);
        header.add(text, BorderLayout.CENTER);
        header.add(versionBadge(), BorderLayout.EAST);
        return header;
    }

    private JComponent versionBadge() {
        JLabel badge = new JLabel(Installer.VERSION) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D canvas = Theme.smooth(graphics);
                canvas.setColor(Theme.SURFACE);
                canvas.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                canvas.setColor(Theme.BORDER);
                canvas.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
                canvas.dispose();
                super.paintComponent(graphics);
            }
        };

        badge.setFont(Theme.ui(Font.BOLD, 11f));
        badge.setForeground(Theme.TEXT_MUTED);
        badge.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));

        // Pinned to the top of the header rather than centred against a two-line
        // title, which is where a version number belongs on every other window
        // the player has open.
        JPanel holder = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        holder.setOpaque(false);
        holder.add(badge);
        return holder;
    }

    private JPanel body() {
        JPanel body = new JPanel(new BorderLayout(0, 14));
        body.setOpaque(false);

        JPanel switcher = new JPanel(new BorderLayout());
        switcher.setOpaque(false);
        switcher.add(FlatControls.segmented(List.of("Client", "Server"),
                index -> pages.show(pageHolder, index == 0 ? "client" : "server")), BorderLayout.WEST);

        pageHolder.setOpaque(false);
        pageHolder.add(clientPage(), "client");
        pageHolder.add(serverPage(), "server");

        body.add(switcher, BorderLayout.NORTH);
        body.add(pageHolder, BorderLayout.CENTER);
        return body;
    }

    // ------------------------------------------------------------------- pages

    private JPanel clientPage() {
        JPanel card = FlatControls.card();
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        FlatButton browse = new FlatButton("Browse", FlatButton.Kind.SECONDARY);
        browse.addActionListener(event -> chooseDirectory(clientDirectory));
        clientDirectory.addActionListener(event -> refreshVersions());

        install.addActionListener(event -> installClient());
        uninstall.addActionListener(event -> uninstallClient());

        int row = 0;
        row = addField(form, row, "Minecraft folder", clientDirectory, browse);
        row = addField(form, row, "Minecraft version", clientVersion, null);

        JPanel actions = FlatControls.row();
        actions.add(install);
        actions.add(uninstall);

        GridBagConstraints constraints = fill(0, row, 3);
        constraints.insets = new Insets(14, 0, 0, 0);
        form.add(actions, constraints);

        constraints = fill(0, row + 1, 3);
        constraints.weighty = 1;
        form.add(javax.swing.Box.createGlue(), constraints);

        card.add(form, BorderLayout.CENTER);
        return card;
    }

    private JPanel serverPage() {
        JPanel card = FlatControls.card();
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));

        FlatButton browse = new FlatButton("Browse", FlatButton.Kind.SECONDARY);
        browse.addActionListener(event -> chooseDirectory(serverDirectory));
        installServer.addActionListener(event -> installServerDirectory());

        int row = 0;
        row = addField(form, row, "Server folder", serverDirectory, browse);
        row = addField(form, row, "Server jar", serverJar, null);

        JPanel actions = FlatControls.row();
        actions.add(installServer);

        GridBagConstraints constraints = fill(0, row, 3);
        constraints.insets = new Insets(14, 0, 0, 0);
        form.add(actions, constraints);

        constraints = fill(0, row + 1, 3);
        constraints.weighty = 1;
        form.add(javax.swing.Box.createGlue(), constraints);

        card.add(form, BorderLayout.CENTER);
        return card;
    }

    private int addField(JPanel form, int row, String label, JComponent input, JComponent trailing) {
        GridBagConstraints constraints = fill(0, row, 3);
        constraints.insets = new Insets(row == 0 ? 0 : 14, 0, 6, 0);
        form.add(FlatControls.fieldLabel(label), constraints);

        constraints = fill(0, row + 1, trailing == null ? 3 : 2);
        constraints.weightx = 1;
        form.add(input, constraints);

        if (trailing != null) {
            constraints = fill(2, row + 1, 1);
            constraints.weightx = 0;
            constraints.insets = new Insets(0, 8, 0, 0);
            form.add(trailing, constraints);
        }

        return row + 2;
    }

    private static GridBagConstraints fill(int column, int row, int width) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.gridwidth = width;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        return constraints;
    }

    private JPanel logPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setOpaque(false);

        output.setEditable(false);
        output.setLineWrap(true);
        output.setWrapStyleWord(true);
        output.setFont(Theme.mono(12f));
        output.setForeground(Theme.TEXT_MUTED);
        output.setBackground(Theme.SURFACE);
        output.setOpaque(false);
        output.setCaretColor(Theme.ACCENT);
        output.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        output.setText("Pick the Minecraft version you already have installed, then press Install.\n");

        status.setFont(Theme.ui(Font.BOLD, 11.5f));
        status.setForeground(Theme.TEXT_MUTED);
        status.setBorder(BorderFactory.createEmptyBorder(6, 26, 6, 13));

        // The log sits in a card of its own, so what the installer did to the
        // disk reads as a record rather than as the bottom of the window.
        JPanel card = FlatControls.card();
        card.add(FlatControls.scroller(output), BorderLayout.CENTER);
        card.setPreferredSize(new Dimension(0, 200));

        JPanel heading = new JPanel(new BorderLayout(12, 0));
        heading.setOpaque(false);
        heading.add(FlatControls.fieldLabel("What the installer did"), BorderLayout.WEST);
        heading.add(statusRow(), BorderLayout.EAST);

        panel.add(heading, BorderLayout.NORTH);
        panel.add(card, BorderLayout.CENTER);
        return panel;
    }

    private JComponent statusRow() {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
        row.setOpaque(false);
        row.add(status);
        return row;
    }

    private void closeOnEscape(JComponent root) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "close");
        root.getActionMap().put("close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                frame.dispose();
            }
        });
    }

    // ------------------------------------------------------------------ actions

    private void chooseDirectory(JTextField target) {
        JFileChooser chooser = new JFileChooser(target.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose a folder");

        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
            refreshVersions();
        }
    }

    private void refreshVersions() {
        Path directory = Path.of(clientDirectory.getText());
        List<String> versions = ClientInstaller.installableVersions(directory);
        clientVersion.setModel(new DefaultComboBoxModel<>(versions.toArray(new String[0])));
        install.setEnabled(!versions.isEmpty());
        uninstall.setEnabled(!versions.isEmpty());

        if (!Files.isDirectory(directory)) {
            state("That folder does not exist", Theme.DANGER);
            return;
        }

        if (versions.isEmpty()) {
            state("No Minecraft versions here", Theme.DANGER);
            log("No Minecraft versions found in " + directory
                    + ". Run the version you want once in the launcher, then come back.");
            return;
        }

        state(versions.size() + " Minecraft version(s) found", Theme.TEXT_MUTED);
    }

    private void installClient() {
        Object selected = clientVersion.getSelectedItem();

        if (selected == null) {
            state("Pick a Minecraft version first", Theme.DANGER);
            return;
        }

        run("Installing for Minecraft " + selected + "...", () -> {
            String profile = new ClientInstaller(Path.of(clientDirectory.getText()), Installer.VERSION, this::log)
                    .install(selected.toString());
            log("");
            log("Done. Select \"" + profile + "\" in your launcher.");
            log("Put mods in " + Path.of(clientDirectory.getText()).resolve("mods")
                    + " — any loader, any version.");
            state("Installed for Minecraft " + selected, Theme.SUCCESS);
        });
    }

    private void uninstallClient() {
        Object selected = clientVersion.getSelectedItem();

        if (selected == null) {
            return;
        }

        // Removing something is worth one question, and it also answers the one
        // people ask afterwards: what exactly did it take away?
        int answer = JOptionPane.showConfirmDialog(frame,
                "Remove Octo Loader for Minecraft " + selected + "?\n\n"
                        + "This deletes the profile it added and the loader library.\n"
                        + "Your mods folder, worlds and settings are left alone.",
                "Uninstall Octo Loader", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);

        if (answer != JOptionPane.OK_OPTION) {
            return;
        }

        run("Removing...", () -> {
            new ClientInstaller(Path.of(clientDirectory.getText()), Installer.VERSION, this::log)
                    .uninstall(selected.toString());
            log("");
            log("Removed. The launcher will fall back to vanilla Minecraft " + selected + ".");
            state("Removed", Theme.SUCCESS);
        });
    }

    private void installServerDirectory() {
        run("Installing into " + serverDirectory.getText() + "...", () -> {
            new ServerInstaller(Path.of(serverDirectory.getText()), Installer.VERSION, this::log)
                    .install(serverJar.getText());
            log("");
            log("Done. Start the server with ./start-octo-server.sh");
            state("Server ready", Theme.SUCCESS);
        });
    }

    /** Runs a job off the event thread with the buttons held down while it works. */
    private void run(String description, Action action) {
        setBusy(true);
        state(description, Theme.TEXT_MUTED);
        log("");
        log("> " + description);

        new Thread(() -> {
            try {
                action.run();
            } catch (Exception e) {
                log("Failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                state("Failed — see the log below", Theme.DANGER);
            } finally {
                SwingUtilities.invokeLater(() -> setBusy(false));
            }
        }, "octo-installer").start();
    }

    private void setBusy(boolean busy) {
        install.setEnabled(!busy);
        uninstall.setEnabled(!busy);
        installServer.setEnabled(!busy);
        frame.setCursor(busy
                ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.WAIT_CURSOR)
                : java.awt.Cursor.getDefaultCursor());
    }

    private void state(String message, Color colour) {
        SwingUtilities.invokeLater(() -> {
            status.setText(message);
            status.setForeground(colour);
            statusColour = colour;
            status.repaint();
        });
    }

    private void log(String message) {
        SwingUtilities.invokeLater(() -> {
            output.append(message + "\n");
            output.setCaretPosition(output.getDocument().getLength());
        });
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private InstallerUi() {
    }
}
