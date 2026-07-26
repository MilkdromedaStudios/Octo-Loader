package studios.milkdromeda.octo.installer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** The installer window. */
final class InstallerUi {
    private final JFrame frame = new JFrame("Octo Installer " + Installer.VERSION);
    private final JTextArea output = new JTextArea(8, 60);

    private final JTextField clientDirectory = new JTextField(MinecraftPaths.defaultGameDirectory().toString(), 30);
    private final JComboBox<String> clientVersion = new JComboBox<>();

    private final JTextField serverDirectory = new JTextField(Path.of("").toAbsolutePath().toString(), 30);
    private final JTextField serverJar = new JTextField("server.jar", 30);

    static void show() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            // The cross-platform look and feel is a perfectly good fallback.
        }

        SwingUtilities.invokeLater(() -> new InstallerUi().open());
    }

    private void open() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Client", clientPanel());
        tabs.addTab("Server", serverPanel());

        output.setEditable(false);
        output.setLineWrap(true);
        output.setText("""
                Octo Loader runs Fabric, Quilt, Forge, NeoForge and older mods side by side,
                including mods built for Minecraft versions other than the one you are running.

                Pick the Minecraft version you already have installed and press Install.
                """);

        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        root.add(tabs, BorderLayout.NORTH);
        root.add(new JScrollPane(output), BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setMinimumSize(new Dimension(640, 460));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        refreshVersions();
    }

    private JPanel clientPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton browse = new JButton("Browse...");
        browse.addActionListener(event -> chooseDirectory(clientDirectory));

        JButton install = new JButton("Install");
        install.addActionListener(event -> installClient());

        JButton uninstall = new JButton("Uninstall");
        uninstall.addActionListener(event -> uninstallClient());

        clientDirectory.addActionListener(event -> refreshVersions());

        int row = 0;
        add(panel, new JLabel("Minecraft folder"), 0, row, 1);
        add(panel, clientDirectory, 1, row, 1);
        add(panel, browse, 2, row++, 1);
        add(panel, new JLabel("Minecraft version"), 0, row, 1);
        add(panel, clientVersion, 1, row++, 1);

        JPanel buttons = new JPanel();
        buttons.add(install);
        buttons.add(uninstall);
        add(panel, buttons, 1, row, 2);

        return panel;
    }

    private JPanel serverPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton browse = new JButton("Browse...");
        browse.addActionListener(event -> chooseDirectory(serverDirectory));

        JButton install = new JButton("Install");
        install.addActionListener(event -> installServer());

        int row = 0;
        add(panel, new JLabel("Server folder"), 0, row, 1);
        add(panel, serverDirectory, 1, row, 1);
        add(panel, browse, 2, row++, 1);
        add(panel, new JLabel("Server jar"), 0, row, 1);
        add(panel, serverJar, 1, row++, 1);
        add(panel, Box.createVerticalStrut(4), 0, row++, 3);
        add(panel, install, 1, row, 1);

        return panel;
    }

    private static void add(JPanel panel, java.awt.Component component, int column, int row, int width) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.gridwidth = width;
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        panel.add(component, constraints);
    }

    private void chooseDirectory(JTextField target) {
        JFileChooser chooser = new JFileChooser(target.getText());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);

        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
            refreshVersions();
        }
    }

    private void refreshVersions() {
        List<String> versions = ClientInstaller.installableVersions(Path.of(clientDirectory.getText()));
        clientVersion.setModel(new DefaultComboBoxModel<>(versions.toArray(new String[0])));

        if (versions.isEmpty()) {
            log("No Minecraft versions found in " + clientDirectory.getText()
                    + ". Run the version you want once in the launcher, then come back.");
        }
    }

    private void installClient() {
        Object selected = clientVersion.getSelectedItem();

        if (selected == null) {
            log("Pick a Minecraft version first.");
            return;
        }

        run(() -> {
            String profile = new ClientInstaller(Path.of(clientDirectory.getText()), Installer.VERSION, this::log)
                    .install(selected.toString());
            log("Done. Select \"" + profile + "\" in your launcher.");
            log("Put mods in " + Path.of(clientDirectory.getText()).resolve("mods") + " - any loader, any version.");
        });
    }

    private void uninstallClient() {
        Object selected = clientVersion.getSelectedItem();

        if (selected == null) {
            return;
        }

        run(() -> {
            new ClientInstaller(Path.of(clientDirectory.getText()), Installer.VERSION, this::log)
                    .uninstall(selected.toString());
            log("Removed.");
        });
    }

    private void installServer() {
        run(() -> {
            new ServerInstaller(Path.of(serverDirectory.getText()), Installer.VERSION, this::log)
                    .install(serverJar.getText());
            log("Done. Start the server with ./start-octo-server.sh");
        });
    }

    private void run(Action action) {
        new Thread(() -> {
            try {
                action.run();
            } catch (Exception e) {
                log("Failed: " + e.getMessage());
            }
        }, "octo-installer").start();
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
