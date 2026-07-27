package studios.milkdromeda.octo.report;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Puts the launch's failures in front of the player as the game starts.
 *
 * <p>Forge draws this inside Minecraft, on a screen of its own. Octo cannot:
 * it is compiled without Minecraft on the class path and runs against whatever
 * version the player has, so there is no {@code Screen} class it can safely
 * extend and no render signature it can safely call. What it can do is what
 * Fabric Loader does — open a window of its own — and that works on every
 * version, including ones released after this code was written.
 *
 * <p>It is deliberately not modal and does not hold the launch up. The game
 * carries on booting behind it, which is the whole point: Minecraft starts,
 * and the reasons your mods are not in it are readable while it does.
 */
public final class ReportWindow {
    private static final OctoLog LOG = OctoLog.of(ReportWindow.class);

    private ReportWindow() {
    }

    /**
     * Shows the report, if there is anything to show and anywhere to show it.
     *
     * @param logFile the persistent log, offered as a button rather than a
     *                sentence nobody reads
     * @return whether a window was opened
     */
    public static boolean show(LoadingReport report, Path logFile) {
        if (report.isEmpty()) {
            return false;
        }

        if (GraphicsEnvironment.isHeadless()) {
            LOG.debug("no display, so the mod report stays in the log");
            return false;
        }

        try {
            SwingUtilities.invokeLater(() -> build(report, logFile));
            return true;
        } catch (Throwable e) {
            // A window that will not open must never be the reason a game does
            // not start. The report is in the log and on disk regardless.
            Failures.rethrowIfFatal(e);
            LOG.warn("could not open the mod report window: {}", e.toString());
            return false;
        }
    }

    private static void build(LoadingReport report, Path logFile) {
        JFrame frame = new JFrame("Octo Loader - " + report.headline());
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        JLabel headline = new JLabel(report.headline());
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, 16f));
        headline.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));

        JLabel note = new JLabel("Minecraft is still starting. Everything below is also in " + logFile + ".");
        note.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));

        JPanel header = new JPanel(new BorderLayout());
        header.add(headline, BorderLayout.NORTH);
        header.add(note, BorderLayout.SOUTH);

        String text = report.render();
        JTextArea body = new JTextArea(text);
        body.setEditable(false);
        body.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        body.setCaretPosition(0);
        body.setBackground(new Color(0x1E, 0x1E, 0x1E));
        body.setForeground(new Color(0xE8, 0xE8, 0xE8));
        body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JButton copy = new JButton("Copy to clipboard");
        copy.addActionListener(ignored -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(text), null));

        JButton open = new JButton("Open the log");
        open.addActionListener(ignored -> openLog(logFile));
        open.setEnabled(Desktop.isDesktopSupported());

        JButton close = new JButton("Close");
        close.addActionListener(ignored -> frame.dispose());

        JPanel buttons = new JPanel();
        buttons.add(copy);
        buttons.add(open);
        buttons.add(close);

        frame.setLayout(new BorderLayout());
        frame.add(header, BorderLayout.NORTH);
        frame.add(new JScrollPane(body), BorderLayout.CENTER);
        frame.add(buttons, BorderLayout.SOUTH);
        frame.setPreferredSize(new Dimension(900, 600));
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void openLog(Path logFile) {
        try {
            Desktop.getDesktop().open(logFile.toFile());
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("could not open {}: {}", logFile, e.toString());
        }
    }
}
