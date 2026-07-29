package studios.milkdromeda.octo.report;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import studios.milkdromeda.octo.ui.FlatButton;
import studios.milkdromeda.octo.ui.Mark;
import studios.milkdromeda.octo.ui.Skin;
import studios.milkdromeda.octo.util.Failures;
import studios.milkdromeda.octo.util.OctoLog;

/**
 * Puts the launch's failures in front of the player, and keeps them there.
 *
 * <p>Forge draws this inside Minecraft, on a screen of its own. Octo cannot: it
 * is compiled without Minecraft on the class path and runs against whatever
 * version the player has, so there is no {@code Screen} class it can safely
 * extend and no render signature it can safely call. What it can do is what
 * Fabric Loader does — open a window of its own — and that works on every
 * version, including ones released after this code was written.
 *
 * <p>The window is a list and a detail pane rather than one long block of text.
 * A folder of forty mods produces a report nobody scrolls through; one line per
 * problem, with the evidence a click away, is a report people read. Each entry
 * leads with what the failure means (see {@link CrashReading}) and keeps the
 * stack trace underneath it, because the stack trace is evidence, not an
 * explanation.
 *
 * <p>It is not modal and does not hold the launch up. The game carries on
 * booting behind it, which is the whole point: Minecraft starts, and the reasons
 * your mods are not in it are readable while it does. If the game then dies,
 * {@link GameWatch} keeps this window — and with it the JVM — alive, because a
 * window that vanishes with the process it was explaining is worse than no
 * window at all.
 */
public final class ReportWindow {
    private static final OctoLog LOG = OctoLog.of(ReportWindow.class);

    /** How long a caller waits for the event thread to put the window up. */
    private static final long BUILD_TIMEOUT_MILLIS = 8_000;

    private static final Object LOCK = new Object();
    private static ReportWindow current;

    private final LoadingReport report;
    private final Path logFile;
    private final CountDownLatch closed = new CountDownLatch(1);

    private final JFrame frame = new JFrame();
    private final JLabel headline = Skin.title("");
    private final JLabel subhead = Skin.caption("");
    private final JPanel counts = Skin.row(8);
    private final JTextField filter = Skin.searchField("Filter by mod or message");
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(model);
    private final Detail detail = new Detail();
    private final JLabel status = Skin.caption("");

    private List<Entry> entries = List.of();

    private ReportWindow(LoadingReport report, Path logFile) {
        this.report = report;
        this.logFile = logFile;
    }

    // ---------------------------------------------------------------- entry points

    /**
     * Shows the report, if there is anything to show and anywhere to show it.
     *
     * @param logFile the persistent log, offered as a button rather than a
     *                sentence nobody reads
     * @return whether a window is on screen
     */
    public static boolean show(LoadingReport report, Path logFile) {
        if (report.isEmpty()) {
            return false;
        }

        if (Boolean.getBoolean("octo.noReportWindow")) {
            LOG.debug("the report window is switched off, so the report stays in the log");
            return false;
        }

        if (GraphicsEnvironment.isHeadless()) {
            LOG.debug("no display, so the mod report stays in the log");
            return false;
        }

        try {
            return open(report, logFile);
        } catch (Throwable e) {
            // A window that will not open must never be the reason a game does
            // not start. The report is in the log and on disk regardless.
            Failures.rethrowIfFatal(e);
            LOG.warn("could not open the mod report window: {}", e.toString());
            return false;
        }
    }

    /** Whether a window is currently on screen. */
    public static boolean isOpen() {
        synchronized (LOCK) {
            return current != null;
        }
    }

    /** Rebuilds the contents from the report, for anything added after it opened. */
    public static void refresh() {
        ReportWindow window = window();

        if (window != null) {
            onSwing(window::populate);
        }
    }

    /**
     * Says that Minecraft is no longer running, and why.
     *
     * <p>The strip along the bottom stops saying the game is still starting and
     * starts saying the window is deliberately still here.
     */
    public static void gameStopped(boolean crashed) {
        ReportWindow window = window();

        if (window != null) {
            onSwing(() -> {
                window.populate();
                window.status.setText(crashed
                        ? "Minecraft stopped. This window stays open until you close it."
                        : "Minecraft has closed.");
                window.status.setForeground(crashed ? Skin.ERROR : Skin.TEXT_MUTED);
            });
        }
    }

    /**
     * Blocks until the player closes the window, or the wait runs out.
     *
     * @return whether the window is now gone
     */
    public static boolean awaitClose(long timeout, TimeUnit unit) {
        ReportWindow window = window();

        if (window == null) {
            return true;
        }

        try {
            return window.closed.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Closes the window if one is open. Used when the game exited cleanly. */
    public static void close() {
        ReportWindow window = window();

        if (window != null) {
            onSwing(window.frame::dispose);
        }
    }

    private static ReportWindow window() {
        synchronized (LOCK) {
            return current;
        }
    }

    private static boolean open(LoadingReport report, Path logFile) {
        synchronized (LOCK) {
            if (current != null) {
                refresh();
                return true;
            }
        }

        if (SwingUtilities.isEventDispatchThread()) {
            build(report, logFile);
            return isOpen();
        }

        CountDownLatch built = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                build(report, logFile);
            } finally {
                built.countDown();
            }
        });

        try {
            built.await(BUILD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return isOpen();
    }

    private static void build(LoadingReport report, Path logFile) {
        try {
            ReportWindow window = new ReportWindow(report, logFile);
            window.assemble();

            synchronized (LOCK) {
                current = window;
            }
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("could not build the mod report window: {}", e.toString());
        }
    }

    private static void onSwing(Runnable work) {
        if (SwingUtilities.isEventDispatchThread()) {
            work.run();
        } else {
            SwingUtilities.invokeLater(work);
        }
    }

    // -------------------------------------------------------------------- chrome

    private void assemble() {
        Skin.applyToDialogs();

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Skin.BACKGROUND);
        root.add(header(), BorderLayout.NORTH);
        root.add(body(), BorderLayout.CENTER);
        root.add(footer(), BorderLayout.SOUTH);

        frame.setContentPane(root);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.setIconImages(Skin.icons());
        frame.setMinimumSize(new Dimension(760, 520));
        frame.setSize(new Dimension(1040, 680));
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                synchronized (LOCK) {
                    if (current == ReportWindow.this) {
                        current = null;
                    }
                }

                closed.countDown();
            }
        });

        shortcuts(root);
        populate();
        frame.setVisible(true);
        frame.toFront();
    }

    private JComponent header() {
        JPanel text = Skin.column();
        headline.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        subhead.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        text.add(headline);
        text.add(javax.swing.Box.createVerticalStrut(4));
        text.add(subhead);

        JPanel bar = new JPanel(new BorderLayout(16, 0));
        bar.setOpaque(false);
        bar.setBorder(Skin.padding(20, 24, 18, 24));
        bar.add(new Mark(40), BorderLayout.WEST);
        bar.add(text, BorderLayout.CENTER);
        bar.add(counts, BorderLayout.EAST);

        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Skin.SURFACE);
        header.add(bar, BorderLayout.CENTER);
        header.add(Skin.divider(), BorderLayout.SOUTH);
        return header;
    }

    private JComponent body() {
        JPanel body = new JPanel(new BorderLayout(0, 0));
        body.setBackground(Skin.BACKGROUND);
        body.setBorder(Skin.padding(16, 16, 12, 16));

        JPanel sidebar = Skin.card();
        sidebar.setPreferredSize(new Dimension(330, 0));
        sidebar.add(filterRow(), BorderLayout.NORTH);
        sidebar.add(problemList(), BorderLayout.CENTER);

        JPanel split = new JPanel(new BorderLayout(14, 0));
        split.setOpaque(false);
        split.add(sidebar, BorderLayout.WEST);
        split.add(detail, BorderLayout.CENTER);

        // The list keeps a third of the window rather than a fixed 330px, so a
        // maximised window gives the stack traces the room and a small one does
        // not give the list more than it needs.
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                int width = Math.max(280, Math.min(430, split.getWidth() / 3));
                sidebar.setPreferredSize(new Dimension(width, 0));
                sidebar.revalidate();
            }
        });

        body.add(split, BorderLayout.CENTER);
        return body;
    }

    private JComponent filterRow() {
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                populate();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                populate();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                populate();
            }
        });

        JPanel row = new JPanel(new BorderLayout(0, 8));
        row.setOpaque(false);
        row.setBorder(Skin.padding(14, 14, 10, 14));
        row.add(Skin.overline("What went wrong"), BorderLayout.NORTH);
        row.add(filter, BorderLayout.CENTER);
        return row;
    }

    private JComponent problemList() {
        list.setCellRenderer(new EntryCell());
        list.setFixedCellHeight(58);
        list.setBackground(Skin.SURFACE);
        list.setOpaque(false);
        list.setBorder(Skin.padding(0, 6, 10, 6));
        list.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                detail.show(list.getSelectedValue(), logFile);
            }
        });

        JScrollPane scroller = Skin.scroller(list);
        scroller.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        return scroller;
    }

    private JComponent footer() {
        FlatButton copy = new FlatButton("Copy report", FlatButton.Kind.SECONDARY);
        copy.addActionListener(event -> copyReport());

        FlatButton openLog = new FlatButton("Open the log", FlatButton.Kind.SECONDARY);
        openLog.addActionListener(event -> open(logFile));
        openLog.setEnabled(Desktop.isDesktopSupported());

        FlatButton showFiles = new FlatButton("Show files", FlatButton.Kind.GHOST);
        showFiles.addActionListener(event -> open(logFile == null ? null : logFile.getParent()));
        showFiles.setEnabled(Desktop.isDesktopSupported());

        FlatButton close = new FlatButton("Close", FlatButton.Kind.PRIMARY);
        close.addActionListener(event -> frame.dispose());

        JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(showFiles);
        actions.add(openLog);
        actions.add(copy);
        actions.add(close);

        status.setBorder(Skin.padding(0, 2, 0, 12));

        JPanel bar = new JPanel(new BorderLayout(12, 0));
        bar.setOpaque(false);
        bar.setBorder(Skin.padding(12, 24, 16, 20));
        bar.add(status, BorderLayout.CENTER);
        bar.add(actions, BorderLayout.EAST);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(Skin.SURFACE);
        footer.add(Skin.divider(), BorderLayout.NORTH);
        footer.add(bar, BorderLayout.CENTER);
        frame.getRootPane().setDefaultButton(close);
        return footer;
    }

    private void shortcuts(JComponent root) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "octo.close");
        root.getActionMap().put("octo.close", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                frame.dispose();
            }
        });

        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menu | java.awt.event.InputEvent.SHIFT_DOWN_MASK),
                        "octo.copy");
        root.getActionMap().put("octo.copy", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                copyReport();
            }
        });
    }

    // ------------------------------------------------------------------ contents

    /** Reads the report again and redraws everything that comes from it. */
    private void populate() {
        entries = Entry.of(report);
        String needle = filter.getText() == null ? "" : filter.getText().strip().toLowerCase(Locale.ROOT);
        Entry selected = list.getSelectedValue();

        // A crash that arrives after the window opened is the news, so it takes
        // the selection away from whatever the player was reading — once.
        boolean crashIsNew = report.hasCrash() && !crashShown;
        crashShown = report.hasCrash();

        model.clear();

        for (Entry entry : entries) {
            if (needle.isEmpty() || entry.matches(needle)) {
                model.addElement(entry);
            }
        }

        if (!model.isEmpty()) {
            int index = selected == null || crashIsNew ? 0 : Math.max(0, model.indexOf(selected));
            list.setSelectedIndex(index);
            list.ensureIndexIsVisible(index);
            detail.show(model.get(index), logFile);
        } else {
            detail.show(null, logFile);
        }

        frame.setTitle("Octo Loader — " + report.headline());
        headline.setText(report.headline());
        subhead.setText(report.hasCrash()
                ? "Minecraft stopped, so this is everything it and Octo recorded."
                : "Minecraft is still starting — this window does not hold it up.");
        subhead.setToolTipText("The whole report is in " + logFile);

        counts.removeAll();
        int errors = report.of(LoadingReport.Severity.ERROR).size();
        int warnings = report.of(LoadingReport.Severity.WARNING).size();

        if (report.hasCrash()) {
            counts.add(Skin.chip("Minecraft stopped", Skin.ERROR));
        }

        if (errors > 0) {
            counts.add(Skin.chip(errors + " not running", Skin.ERROR));
        }

        if (warnings > 0) {
            counts.add(Skin.chip(warnings + " adjusted", Skin.WARNING));
        }

        counts.revalidate();
        counts.repaint();

        if (report.hasCrash()) {
            status.setText("Minecraft stopped. This window stays open until you close it.");
            status.setForeground(Skin.ERROR);
        } else {
            status.setText("Saved to " + logFile);
            status.setForeground(Skin.TEXT_MUTED);
        }
    }

    /** Whether the crash has already taken the selection once. */
    private boolean crashShown;

    private void copyReport() {
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(report.render()), null);
            status.setText("Copied the whole report to the clipboard.");
            status.setForeground(Skin.SUCCESS);
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("could not copy the report: {}", e.toString());
            status.setText("The clipboard refused the report — it is in " + logFile + ".");
            status.setForeground(Skin.WARNING);
        }
    }

    private void open(Path path) {
        if (path == null) {
            return;
        }

        try {
            Desktop.getDesktop().open(path.toFile());
        } catch (Throwable e) {
            Failures.rethrowIfFatal(e);
            LOG.warn("could not open {}: {}", path, e.toString());
            status.setText("Could not open " + path + " — open it by hand.");
            status.setForeground(Skin.WARNING);
        }
    }

    // ------------------------------------------------------------------- entries

    /** One row in the list: a crash, a mod that is not running, or an adjustment. */
    private record Entry(Kind kind, String source, String summary, String detail, List<String> steps,
            String meaning) {
        static List<Entry> of(LoadingReport report) {
            List<Entry> out = new ArrayList<>();

            report.crash().ifPresent(crash -> {
                CrashReading reading = CrashReading.of(crash.detail());
                StringBuilder body = new StringBuilder(crash.detail());

                if (crash.file() != null) {
                    body.append(System.lineSeparator()).append(System.lineSeparator())
                            .append("Minecraft's own crash report: ").append(crash.file());
                }

                out.add(new Entry(Kind.CRASH, "Minecraft",
                        crash.phase().isBlank() ? "the game stopped" : "stopped while: " + crash.phase(),
                        body.toString(), reading.steps(), reading.meaning()));
            });

            for (LoadingReport.Problem problem : report.of(LoadingReport.Severity.ERROR)) {
                out.add(from(Kind.ERROR, problem));
            }

            for (LoadingReport.Problem problem : report.of(LoadingReport.Severity.WARNING)) {
                out.add(from(Kind.WARNING, problem));
            }

            return List.copyOf(out);
        }

        private static Entry from(Kind kind, LoadingReport.Problem problem) {
            CrashReading reading = CrashReading.of(problem.detail());
            boolean useful = reading.isRecognised();
            return new Entry(kind, problem.source(), problem.summary(), problem.detail(),
                    useful ? reading.steps() : List.of(), useful ? reading.meaning() : "");
        }

        boolean matches(String needle) {
            return source.toLowerCase(Locale.ROOT).contains(needle)
                    || summary.toLowerCase(Locale.ROOT).contains(needle)
                    || detail.toLowerCase(Locale.ROOT).contains(needle);
        }
    }

    private enum Kind {
        CRASH(Skin.ERROR, "Minecraft stopped"),
        ERROR(Skin.ERROR, "Not running"),
        WARNING(Skin.WARNING, "Loaded with changes");

        private final Color colour;
        private final String label;

        Kind(Color colour, String label) {
            this.colour = colour;
            this.label = label;
        }
    }

    /** Two lines and a dot, drawn rather than laid out, so long names elide cleanly. */
    private static final class EntryCell extends JComponent implements ListCellRenderer<Entry> {
        private Entry entry;
        private boolean selected;

        @Override
        public java.awt.Component getListCellRendererComponent(JList<? extends Entry> list, Entry value,
                int index, boolean isSelected, boolean hasFocus) {
            this.entry = value;
            this.selected = isSelected;
            setToolTipText(value == null ? null : value.source() + " — " + value.summary());
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(220, 58);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (entry == null) {
                return;
            }

            Graphics2D canvas = Skin.smooth(graphics);
            int width = getWidth();
            int height = getHeight();

            if (selected) {
                canvas.setColor(Skin.SURFACE_RAISED);
                canvas.fillRoundRect(2, 2, width - 4, height - 4, 9, 9);
                canvas.setColor(entry.kind().colour);
                canvas.fillRoundRect(2, 8, 3, height - 16, 3, 3);
            }

            canvas.setColor(entry.kind().colour);
            canvas.fillOval(14, 15, 7, 7);

            canvas.setFont(Skin.ui(Font.BOLD, 13f));
            canvas.setColor(Skin.TEXT);
            canvas.drawString(elide(canvas, entry.source(), width - 40), 30, 22);

            canvas.setFont(Skin.ui(Font.PLAIN, 11.5f));
            canvas.setColor(Skin.TEXT_MUTED);
            canvas.drawString(elide(canvas, entry.summary(), width - 44), 30, 40);

            canvas.dispose();
        }

        private static String elide(Graphics2D canvas, String text, int available) {
            if (canvas.getFontMetrics().stringWidth(text) <= available) {
                return text;
            }

            String shortened = text;

            while (shortened.length() > 1
                    && canvas.getFontMetrics().stringWidth(shortened + "…") > available) {
                shortened = shortened.substring(0, shortened.length() - 1);
            }

            return shortened + "…";
        }
    }

    // -------------------------------------------------------------------- detail

    /**
     * The right-hand pane: what this is, what it means, and the evidence.
     *
     * <p>Everything scrolls together in one column rather than sitting in two
     * panes with a scroll bar each. That is not only simpler to read — it is the
     * arrangement in which wrapped text lays out correctly, because the column
     * takes the viewport's width and every paragraph in it can then work out how
     * tall it needs to be.
     */
    private static final class Detail extends JPanel {
        private final JLabel kind = Skin.caption("");
        private final JTextArea title = wrapped(Skin.ui(Font.BOLD, 15f), Skin.TEXT);
        private final JTextArea meaning = wrapped(Skin.ui(Font.PLAIN, 13f), Skin.TEXT);
        private final JPanel steps = Skin.column();
        private final JPanel advice = Skin.card(Skin.SURFACE_RAISED);
        private final JLabel evidenceLabel = Skin.overline("The detail");
        private final JTextArea evidence = wrapped(Skin.mono(12f), Skin.TEXT_MUTED);
        private final Column column = new Column();

        Detail() {
            super(new BorderLayout());
            setOpaque(false);

            kind.setFont(Skin.ui(Font.BOLD, 10.5f));
            evidence.setBorder(Skin.padding(8, 0, 0, 0));

            // Stack traces wrap at the edge rather than at spaces: a line broken
            // between words leaves a stray "at" on a line of its own, while one
            // broken at the margin still reads as the single line it is.
            evidence.setWrapStyleWord(false);

            advice.setLayout(new BorderLayout());
            advice.setBorder(Skin.padding(12, 14, 14, 14));
            JPanel adviceBody = Skin.column();
            adviceBody.add(stretch(Skin.overline("What to try")));
            adviceBody.add(javax.swing.Box.createVerticalStrut(8));
            adviceBody.add(stretch(steps));
            advice.add(adviceBody, BorderLayout.CENTER);

            column.setBorder(Skin.padding(18, 20, 18, 20));
            column.add(stretch(kind));
            column.add(javax.swing.Box.createVerticalStrut(8));
            column.add(stretch(title));
            column.add(javax.swing.Box.createVerticalStrut(10));
            column.add(stretch(meaning));
            column.add(javax.swing.Box.createVerticalStrut(16));
            column.add(stretch(advice));
            column.add(javax.swing.Box.createVerticalStrut(18));
            column.add(stretch(evidenceLabel));
            column.add(stretch(evidence));

            JPanel card = Skin.card();
            card.add(Skin.scroller(column), BorderLayout.CENTER);
            add(card, BorderLayout.CENTER);
        }

        void show(Entry entry, Path logFile) {
            steps.removeAll();

            if (entry == null) {
                kind.setText("");
                title.setText("Nothing matches that filter");
                meaning.setText("Clear the filter to see the whole report.");
                advice.setVisible(false);
                evidenceLabel.setVisible(false);
                evidence.setText("");
                refresh();
                return;
            }

            kind.setText(entry.kind().label.toUpperCase(Locale.ROOT));
            kind.setForeground(entry.kind().colour);
            title.setText(entry.source() + " — " + entry.summary());
            meaning.setText(entry.meaning().isBlank()
                    ? "Octo recorded this while loading your mods. What it was given is below."
                    : entry.meaning());

            for (String step : entry.steps()) {
                JTextArea line = wrapped(Skin.ui(Font.PLAIN, 12.5f), Skin.TEXT_MUTED);
                line.setText("•  " + step);
                line.setBorder(Skin.padding(0, 0, 7, 0));
                steps.add(stretch(line));
            }

            advice.setVisible(!entry.steps().isEmpty());
            evidenceLabel.setVisible(true);
            evidence.setText(entry.detail().isBlank()
                    ? "Octo recorded nothing further. The whole launch log is in " + logFile + "."
                    : entry.detail());
            refresh();
        }

        private void refresh() {
            column.revalidate();
            column.repaint();
            SwingUtilities.invokeLater(() -> {
                JScrollPane scroller = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, column);

                if (scroller != null) {
                    scroller.getVerticalScrollBar().setValue(0);
                }
            });
        }

        private static JTextArea wrapped(Font font, Color colour) {
            JTextArea area = new JTextArea();
            area.setFont(font);
            area.setForeground(colour);
            area.setOpaque(false);
            area.setEditable(false);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setBorder(BorderFactory.createEmptyBorder());
            return area;
        }

        /** Left-aligned, and allowed to take the column's full width. */
        private static JComponent stretch(JComponent component) {
            component.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            component.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
            return component;
        }

        /**
         * A vertical stack that is exactly as wide as the viewport showing it.
         *
         * <p>Without this a wrapping text area is asked how tall it wants to be
         * before anything has told it how wide it is, answers "one line", and
         * loses the rest of the paragraph.
         */
        private static final class Column extends JPanel implements javax.swing.Scrollable {
            Column() {
                setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
                setOpaque(false);
            }

            @Override
            public Dimension getPreferredScrollableViewportSize() {
                return getPreferredSize();
            }

            @Override
            public int getScrollableUnitIncrement(java.awt.Rectangle visible, int orientation, int direction) {
                return 18;
            }

            @Override
            public int getScrollableBlockIncrement(java.awt.Rectangle visible, int orientation, int direction) {
                return orientation == javax.swing.SwingConstants.VERTICAL
                        ? visible.height - 24 : visible.width - 24;
            }

            @Override
            public boolean getScrollableTracksViewportWidth() {
                return true;
            }

            @Override
            public boolean getScrollableTracksViewportHeight() {
                return false;
            }
        }
    }
}
